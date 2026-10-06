"""AST analyzer over yt_dlp/extractor/: per-extractor capture + classification.

Captures IE_NAME, _VALID_URL (statically resolved), one test URL, _real_extract
shape (LOC, download calls, POST usage, complexity tokens, traversal patterns)
and classifies each extractor as SIMPLE / POST_API / JS_COMPLEX / OTHER.

stdlib only.
"""

from __future__ import annotations

import ast
import os
from dataclasses import dataclass, field

SIMPLE = "SIMPLE"
POST_API = "POST_API"
JS_COMPLEX = "JS_COMPLEX"
OTHER = "OTHER"

SIMPLE_LOC_CAP = 60  # task: "≤ ~60 LOC _real_extract"

DOWNLOAD_FUNCS = {
    "_download_json", "_download_json_handle",
    "_download_webpage", "_download_webpage_handle",
    "_download_xml",
}
SIMPLE_DOWNLOAD_FUNCS = {"_download_json", "_download_webpage"}

# identifier-level complexity signals (substring match, lowercase)
_COMPLEX_SUBSTR = (
    "signature", "nsig", "decrypt", "aes", "cipher", "challenge",
    "protobuf", "ejs", "obfusc", "js_to", "player_js", "sig_js",
)
_COMPLEX_EXACT = {"js", "wasm", "n_param"}

_TRAVERSAL_FUNCS = {"try_get", "dict_get", "traverse_to"}
_MAX_TRAVERSAL_FOR_SIMPLE = 6


@dataclass
class DownloadCall:
    func: str
    url_expr: ast.AST | None
    has_data: bool
    has_headers: bool
    target_var: str | None
    line: int


@dataclass
class ExtractorRecord:
    module: str
    cls: str
    ie_name: str
    valid_url: str | None = None
    valid_url_note: str = "own"
    test_url: str | None = None
    working: bool = True
    has_real_extract: bool = False
    loc: int = 0
    download_calls: list[DownloadCall] = field(default_factory=list)
    post: bool = False
    complex_tokens: list[str] = field(default_factory=list)
    traversal_calls: int = 0
    search_regex_patterns: list[str] = field(default_factory=list)
    json_var: str | None = None
    webpage_var: str | None = None
    assigns: dict[str, ast.AST] = field(default_factory=dict)
    id_group: str | None = None      # 'id' when video_id = self._match_id(url)
    id_group_pos: int | None = None  # N when m.group(N) feeds the URL
    id_group_name: str | None = None # name when m.group('name') feeds the URL
    json_pointers: list[str] = field(default_factory=list)
    # pointer -> provenance flags (subset of direct/nested/loop/traversal)
    json_pointer_prov: dict[str, str] = field(default_factory=dict)
    class_consts: dict[str, str] = field(default_factory=dict)
    module_string_consts: dict[str, str] = field(default_factory=dict)
    parse_error: str | None = None
    classification: str = OTHER
    classify_reason: str = ""

    def as_csv_row(self) -> dict:
        return {
            "module": self.module,
            "class": self.cls,
            "ie_name": self.ie_name,
            "classification": self.classification,
            "reason": self.classify_reason,
            "loc": self.loc,
            "downloads": len(self.download_calls),
            "has_valid_url": bool(self.valid_url),
            "has_test_url": bool(self.test_url),
            "post": self.post,
            "complex_tokens": ";".join(sorted(set(self.complex_tokens))),
        }


# ------------------------------------------------------------------ helpers

def _class_is_extractor(node: ast.ClassDef) -> bool:
    for base in node.bases:
        name = None
        if isinstance(base, ast.Name):
            name = base.id
        elif isinstance(base, ast.Attribute):
            name = base.attr
        if name and (name.endswith("IE") or name.endswith("InfoExtractor") or name.endswith("Extractor")):
            return True
    return node.name.endswith("IE")


def _const_str(node: ast.AST) -> str | None:
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    return None


def _class_attr(node: ast.ClassDef, name: str) -> ast.AST | None:
    for stmt in node.body:
        if isinstance(stmt, ast.Assign):
            for target in stmt.targets:
                if isinstance(target, ast.Name) and target.id == name:
                    return stmt.value
        elif isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name) and stmt.target.id == name:
            return stmt.value
    return None


def _module_consts(tree: ast.Module) -> dict[str, ast.AST]:
    out: dict[str, ast.AST] = {}
    for stmt in tree.body:
        if isinstance(stmt, ast.Assign):
            for target in stmt.targets:
                if isinstance(target, ast.Name):
                    out.setdefault(target.id, stmt.value)
    return out


class _StaticResolver:
    """Bounded static string evaluation for _VALID_URL-shaped expressions."""

    def __init__(self, class_urls: dict[tuple[str, str], ast.AST], module_consts: dict[str, dict[str, ast.AST]]):
        # class_urls: (module, class name) -> _VALID_URL expr
        self.class_urls = class_urls
        self.module_consts = module_consts
        self.global_classes: dict[str, tuple[str, ast.AST]] = {}
        for (mod, cls), expr in class_urls.items():
            self.global_classes.setdefault(cls, (mod, expr))

    def resolve(self, expr: ast.AST, module: str, depth: int = 0) -> str | None:
        if depth > 8:
            return None
        if isinstance(expr, ast.Constant):
            return expr.value if isinstance(expr.value, str) else None
        if isinstance(expr, ast.BinOp) and isinstance(expr.op, ast.Add):
            left = self.resolve(expr.left, module, depth + 1)
            right = self.resolve(expr.right, module, depth + 1)
            if left is not None and right is not None:
                return left + right
            return None
        if isinstance(expr, ast.BinOp) and isinstance(expr.op, ast.Mod):
            template = self.resolve(expr.left, module, depth + 1)
            if template is None:
                return None
            right = expr.right
            if isinstance(right, ast.Dict):
                mapping = {}
                for k, v in zip(right.keys, right.values):
                    key = _const_str(k) if k is not None else None
                    val = self.resolve(v, module, depth + 1)
                    if key is None or val is None:
                        return None
                    mapping[key] = val
                try:
                    return template % mapping
                except (KeyError, TypeError, ValueError):
                    return None
            values = []
            if isinstance(right, ast.Tuple):
                for elt in right.elts:
                    val = self.resolve(elt, module, depth + 1)
                    if val is None:
                        return None
                    values.append(val)
            elif isinstance(right, ast.Name) or isinstance(right, ast.Attribute):
                return None  # % var (video id at runtime) — not static
            else:
                val = self.resolve(right, module, depth + 1)
                if val is None:
                    return None
                values.append(val)
            try:
                return template % tuple(values)
            except (TypeError, ValueError):
                return None
        if isinstance(expr, ast.JoinedStr):
            parts = []
            for value in expr.values:
                if isinstance(value, ast.Constant) and isinstance(value.value, str):
                    parts.append(value.value)
                elif isinstance(value, ast.FormattedValue):
                    inner = self.resolve(value.value, module, depth + 1)
                    if inner is None:
                        return None
                    parts.append(inner)
                else:
                    return None
            return "".join(parts)
        if isinstance(expr, ast.Call) and isinstance(expr.func, ast.Attribute) and expr.func.attr == "format":
            template = self.resolve(expr.func.value, module, depth + 1)
            if template is None:
                return None
            kwargs = {}
            for kw in expr.keywords:
                val = self.resolve(kw.value, module, depth + 1)
                if val is None:
                    return None
                kwargs[kw.arg] = val
            args = []
            for arg in expr.args:
                val = self.resolve(arg, module, depth + 1)
                if val is None:
                    return None
                args.append(val)
            try:
                return template.format(*args, **kwargs)
            except (KeyError, IndexError, TypeError, ValueError):
                return None
        if isinstance(expr, ast.Call) and isinstance(expr.func, ast.Name) and expr.func.id == "join":
            return None
        if isinstance(expr, ast.Attribute) and expr.attr == "_VALID_URL":
            base = expr.value
            if isinstance(base, ast.Name):
                cls = base.id
                if (module, cls) in self.class_urls:
                    return self.resolve(self.class_urls[(module, cls)], module, depth + 1)
                if cls in self.global_classes:
                    mod2, expr2 = self.global_classes[cls]
                    return self.resolve(expr2, mod2, depth + 1)
            return None
        if isinstance(expr, ast.Name):
            local = self.module_consts.get(module, {}).get(expr.id)
            if local is not None:
                return self.resolve(local, module, depth + 1)
            return None
        return None


# ------------------------------------------------------------------ analysis

def _analyze_real_extract(fn: ast.FunctionDef, rec: ExtractorRecord) -> None:
    rec.loc = (fn.end_lineno or fn.lineno) - fn.lineno + 1
    assigns: dict[str, ast.AST] = {}
    for stmt in ast.walk(fn):
        if isinstance(stmt, ast.Assign) and isinstance(stmt.value, ast.AST):
            for target in stmt.targets:
                if isinstance(target, ast.Name):
                    assigns.setdefault(target.id, stmt.value)
                elif isinstance(target, ast.Tuple) and isinstance(stmt.value, ast.Tuple):
                    for sub_target, sub_value in zip(target.elts, stmt.value.elts):
                        if isinstance(sub_target, ast.Name):
                            assigns.setdefault(sub_target.id, sub_value)
                elif isinstance(target, ast.Tuple) and isinstance(stmt.value, ast.Call) \
                        and isinstance(stmt.value.func, ast.Attribute) \
                        and stmt.value.func.attr == "group":
                    # `kind, slug = mobj.group('kind', 'slug')` unpacking idiom
                    for sub_target, arg in zip(target.elts, stmt.value.args):
                        if isinstance(sub_target, ast.Name) and isinstance(arg, ast.Constant) \
                                and isinstance(arg.value, str):
                            group_call = ast.Call(
                                func=stmt.value.func, args=[arg], keywords=[])
                            assigns.setdefault(sub_target.id, group_call)
                elif isinstance(target, ast.Tuple) and isinstance(stmt.value, ast.Call) \
                        and isinstance(stmt.value.func, ast.Attribute) \
                        and stmt.value.func.attr == "groups" and not stmt.value.args:
                    # `uploader, date = self._match_valid_url(url).groups()`:
                    # positional groups in declaration order
                    for pos, sub_target in enumerate(target.elts, start=1):
                        if isinstance(sub_target, ast.Name):
                            group_attr = ast.Attribute(
                                value=stmt.value.func.value, attr="group", ctx=ast.Load())
                            group_call = ast.Call(
                                func=group_attr, args=[ast.Constant(pos)], keywords=[])
                            assigns.setdefault(sub_target.id, group_call)
    rec.assigns = assigns

    for node in ast.walk(fn):
        if not isinstance(node, ast.Call):
            continue
        func = node.func
        attr = func.attr if isinstance(func, ast.Attribute) else (
            func.id if isinstance(func, ast.Name) else None
        )
        if attr in DOWNLOAD_FUNCS:
            url_expr = node.args[0] if node.args else None
            has_data = any(
                kw.arg == "data" and not (
                    isinstance(kw.value, ast.Constant) and kw.value.value is None
                )
                for kw in node.keywords
            )
            headers_kw = next((kw for kw in node.keywords if kw.arg == "headers"), None)
            has_headers = headers_kw is not None and not (
                isinstance(headers_kw.value, ast.Constant) and not headers_kw.value.value
            )
            target_var = None
            for var, value in assigns.items():
                if value is node:
                    target_var = var
                    break
            rec.download_calls.append(DownloadCall(attr, url_expr, has_data, has_headers, target_var, node.lineno))
            if attr in ("_download_json", "_download_json_handle") and target_var and not rec.json_var:
                rec.json_var = target_var
            if attr in ("_download_webpage", "_download_webpage_handle") and target_var and not rec.webpage_var:
                rec.webpage_var = target_var
        elif attr in ("_search_regex", "_html_search_regex") and node.args:
            pattern = _const_str(node.args[0])
            if pattern is not None:
                rec.search_regex_patterns.append(pattern)
        elif attr in _TRAVERSAL_FUNCS:
            rec.traversal_calls += 1
        elif attr in ("urlopen", "post") or (attr == "Request" and isinstance(func, ast.Name)):
            rec.post = rec.post or attr == "post"

    # identifier-level complexity tokens
    for node in ast.walk(fn):
        ident: str | None = None
        if isinstance(node, ast.Attribute):
            ident = node.attr
        elif isinstance(node, ast.Name):
            ident = node.id
        if not ident:
            continue
        low = ident.lower()
        if low in _COMPLEX_EXACT or any(tok in low for tok in _COMPLEX_SUBSTR):
            rec.complex_tokens.append(low)

    # POST via data= on any download call
    if any(dc.has_data for dc in rec.download_calls):
        rec.post = True

    # id provenance: video_id = self._match_id(url)  ->  group 'id'
    for var, value in assigns.items():
        if (
            isinstance(value, ast.Call)
            and isinstance(value.func, ast.Attribute)
            and value.func.attr == "_match_id"
        ):
            rec.id_group = "id"
            rec.id_group_name = "id"
            break

    # JSON variable also reached via `json.loads(<webpage var or inline download>)`
    if not rec.json_var:
        for var, value in assigns.items():
            if (
                isinstance(value, ast.Call)
                and isinstance(value.func, ast.Attribute)
                and value.func.attr == "loads"
                and value.args
                and _wraps_download(value.args[0], rec.webpage_var)
            ):
                rec.json_var = var
                break

    # json pointer synthesis on the downloaded-JSON variable (T108 root cause 1):
    # recursive value-walk emulation over the AST — nested subscript/.get chains,
    # intermediate assignments, loop variables and traversal-helper tuples.
    if rec.json_var:
        rec.json_pointers, rec.json_pointer_prov = _collect_json_pointers(fn, rec.json_var)


# A response path is a tuple of segments: str = dict key, ("i", n) = array
# index, ("*",) = iteration over an array (concretized to [0], flagged 'loop').
MAX_POINTER_DEPTH = 6
MAX_POINTER_CHARS = 64

# helpers whose (root, path-spec) arguments reveal response shapes. NOTE:
# traverse_obj is tracked for POINTER SYNTHESIS only — classification
# heaviness (_TRAVERSAL_FUNCS) is unchanged.
_PATH_HELPERS = _TRAVERSAL_FUNCS | {"traverse_obj"}

_URL_LEAF_HINTS = (
    "url", "file", "manifest", "src", "source", "stream", "playback",
    "media", "content", "videolink", "hls", "dash", "mp4", "m3u8", "video",
)
_QUALITY_LEAVES = {
    "height", "width", "quality", "label", "resolution", "bitrate",
    "tbr", "abr", "fps", "ext", "format", "formatid",
}


def _is_identifier_key(key: str) -> bool:
    return bool(key) and key.replace("-", "_").isidentifier() and not key[0].isdigit()


def _chain_path(expr: ast.AST, env: dict[str, tuple]) -> tuple | None:
    """Resolve a subscript/.get chain rooted at an env-tracked variable."""
    segs: list = []
    node = expr
    while True:
        if isinstance(node, ast.Subscript):
            sl = node.slice
            if isinstance(sl, ast.Constant):
                if isinstance(sl.value, str):
                    if not _is_identifier_key(sl.value):
                        return None
                    segs.append(sl.value)
                elif isinstance(sl.value, int) and 0 <= sl.value < 1000:
                    segs.append(("i", sl.value))
                else:
                    return None
            else:
                return None  # computed subscript
            node = node.value
            continue
        if (
            isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and node.func.attr == "get"
            and node.args
            and isinstance(node.args[0], ast.Constant)
            and isinstance(node.args[0].value, str)
        ):
            if not _is_identifier_key(node.args[0].value):
                return None
            segs.append(node.args[0].value)
            node = node.func.value
            continue
        break
    if isinstance(node, ast.Name) and node.id in env:
        return env[node.id] + tuple(reversed(segs))
    return None


def _is_path_helper(node: ast.Call) -> bool:
    if isinstance(node.func, ast.Name):
        return node.func.id in _PATH_HELPERS
    if isinstance(node.func, ast.Attribute):
        return node.func.attr in _PATH_HELPERS
    return False


def _traversal_path(node: ast.Call, env: dict[str, tuple]) -> tuple | None:
    """try_get/dict_get(rooted, ('key', 0, ...)) and traverse_obj(rooted,
    ('data', ..., 'url', {filter}, lambda ...)) -> path tuple. Type filters
    ({str}, {url_or_none}) are skipped; element filters (lambdas, `...`,
    builtins like `any`) concretize as array iteration."""
    if not (len(node.args) >= 2):
        return None
    root = _chain_path(node.args[0], env)
    if root is None:
        return None
    spec = node.args[1]
    elts = spec.elts if isinstance(spec, (ast.Tuple, ast.List)) else [spec]
    segs: list = []
    for elt in elts:
        if isinstance(elt, ast.Constant):
            value = elt.value
            if isinstance(value, str):
                if not _is_identifier_key(value):
                    return None
                segs.append(value)
            elif isinstance(value, bool):
                return None
            elif isinstance(value, int) and 0 <= value < 1000:
                segs.append(("i", value))
            elif value is Ellipsis:
                segs.append(("*",))
            elif value is None:
                continue
            else:
                return None
        elif isinstance(elt, ast.Lambda):
            segs.append(("*",))  # per-element predicate: array iteration
        elif isinstance(elt, (ast.Set, ast.List)):
            continue  # {type}/[{type}] filters: transparent
        else:
            return None
    return root + tuple(segs)


def _concretize(path: tuple) -> tuple[str, str] | None:
    """path -> (pointer, provenance-flags); None when not expressible/bounded."""
    parts: list[str] = []
    flags: set[str] = set()
    keys = 0
    for seg in path:
        if isinstance(seg, str):
            if not _is_identifier_key(seg):
                return None
            parts.append(seg)
            keys += 1
        elif isinstance(seg, tuple):
            if not parts:
                return None  # index before any key: not expressible
            if seg[0] == "*":
                parts[-1] += "[0]"
                flags.add("loop")
            elif seg[0] == "i":
                parts[-1] += "[%d]" % seg[1]
            else:
                return None
        else:
            return None
    if not parts or keys > MAX_POINTER_DEPTH or len(parts) > MAX_POINTER_DEPTH:
        return None
    pointer = ".".join(parts)
    if len(pointer) > MAX_POINTER_CHARS:
        return None
    if len(parts) > 1:
        flags.add("nested")
    if not flags:
        flags.add("direct")
    return pointer, ",".join(sorted(flags))


def _flag_rank(flags: str) -> int:
    score = 0
    if "loop" in flags:
        score += 2
    if "traversal" in flags:
        score += 2
    if "nested" in flags:
        score += 1
    return score


def _leaf_rank(pointer: str) -> int:
    leaf = pointer.split(".")[-1].split("[")[0].lower()
    if "url" in leaf or leaf in _URL_LEAF_HINTS:
        return 0
    if leaf in _QUALITY_LEAVES:
        return 1
    return 2


def _collect_json_pointers(fn: ast.FunctionDef, var: str) -> tuple[list[str], dict[str, str]]:
    """Dot-paths of response accesses: chains on the JSON variable plus
    everything reachable through intermediate assigns, loop variables and
    traversal helpers (T108 recursive value-walk over the AST)."""
    env: dict[str, tuple] = {var: ()}
    ambiguous: set[str] = set()

    # environment: assignments and for-loops over rooted chains (flow-insensitive)
    for node in ast.walk(fn):
        if isinstance(node, ast.Assign):
            value = node.value
            path = _chain_path(value, env)
            if path is None and isinstance(value, ast.Call) and _is_path_helper(value):
                path = _traversal_path(value, env)
            if path is not None:
                for target in node.targets:
                    if isinstance(target, ast.Name):
                        if target.id in env and env[target.id] != path:
                            ambiguous.add(target.id)
                        env[target.id] = path
        elif isinstance(node, ast.For):
            path = _chain_path(node.iter, env)
            if path is None and isinstance(node.iter, ast.Call) and _is_path_helper(node.iter):
                path = _traversal_path(node.iter, env)
            if path is not None:
                for target in node.target.elts if isinstance(node.target, ast.Tuple) else [node.target]:
                    if isinstance(target, ast.Name):
                        if target.id in env and env[target.id] != path + (("*",),):
                            ambiguous.add(target.id)
                        env[target.id] = path + (("*",),)

    for name in ambiguous:
        env.pop(name, None)

    # every rooted access anywhere becomes a candidate path (-> came from a
    # traversal helper); provenance flags are computed at concretization
    found: dict[tuple, bool] = {}
    for node in ast.walk(fn):
        path: tuple | None = None
        traversal = False
        if isinstance(node, ast.Subscript):
            path = _chain_path(node, env)
        elif isinstance(node, ast.Call):
            if isinstance(node.func, ast.Attribute) and node.func.attr == "get":
                path = _chain_path(node, env)
            elif _is_path_helper(node):
                path = _traversal_path(node, env)
                traversal = True
        if path is None:
            continue
        found[path] = found.get(path, False) or traversal

    # drop prefix chains subsumed by a deeper access (info['files']['mp4'] is
    # not a pointer when info['files']['mp4'][0]['url'] is recorded)
    paths = list(found)
    for path in paths:
        for other in paths:
            if len(other) > len(path) and other[: len(path)] == path:
                found.pop(path, None)
                break

    pointers: dict[str, str] = {}
    for path, traversal in found.items():
        got = _concretize(path)
        if got is None:
            continue
        pointer, concretized = got
        if traversal:
            concretized = ",".join(sorted(set(concretized.split(",")) | {"traversal"}))
        old = pointers.get(pointer)
        if old is None or _flag_rank(concretized) < _flag_rank(old):
            pointers[pointer] = concretized

    ranked = sorted(pointers, key=lambda p: (_leaf_rank(p), p.count(".") + p.count("["), p))
    out = ranked[:8]
    return out, {p: pointers[p] for p in out}


def _wraps_download(expr: ast.AST, webpage_var: str | None) -> bool:
    """True for <webpage_var> or any expression containing a download call."""
    if webpage_var and isinstance(expr, ast.Name) and expr.id == webpage_var:
        return True
    for node in ast.walk(expr):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
            if node.func.attr in DOWNLOAD_FUNCS:
                return True
    return False


def _first_test_url(node: ast.AST) -> str | None:
    """First dict entry's constant 'url' from _TESTS/_TEST class attributes."""
    if isinstance(node, (ast.List, ast.Tuple)):
        for elt in node.elts:
            url = _first_test_url(elt)
            if url:
                return url
        return None
    if isinstance(node, ast.Dict):
        for k, v in zip(node.keys, node.values):
            if isinstance(k, ast.Constant) and k.value == "url":
                return _const_str(v)
        return None
    return None


# ------------------------------------------------------------------ pipeline

def analyze_module(path: str, tree: ast.Module, resolver: _StaticResolver) -> list[ExtractorRecord]:
    module = os.path.basename(path)
    out: list[ExtractorRecord] = []
    module_class_urls: dict[tuple[str, str], ast.AST] = {}
    classes: list[ast.ClassDef] = []
    for stmt in tree.body:
        if isinstance(stmt, ast.ClassDef) and _class_is_extractor(stmt):
            classes.append(stmt)
            expr = _class_attr(stmt, "_VALID_URL")
            if expr is not None:
                module_class_urls[(module, stmt.name)] = expr
    resolver.class_urls.update(module_class_urls)

    # class-level string constants, inherited from same-module base classes
    own_consts: dict[str, dict[str, str]] = {}
    for cls in classes:
        own: dict[str, str] = {}
        for stmt in cls.body:
            if isinstance(stmt, ast.Assign) and isinstance(stmt.value, ast.Constant) and isinstance(stmt.value.value, str):
                for target in stmt.targets:
                    if isinstance(target, ast.Name):
                        own[target.id] = stmt.value.value
        own_consts[cls.name] = own

    def merged_consts(cls: ast.ClassDef, seen: set[str]) -> dict[str, str]:
        out: dict[str, str] = {}
        for base in cls.bases:
            if isinstance(base, ast.Name) and base.id in own_consts and base.id not in seen:
                out.update(merged_consts(base_with_name(base.id), seen | {base.id}))
        out.update(own_consts.get(cls.name, {}))
        return out

    def base_with_name(name: str) -> ast.ClassDef:
        for cls in classes:
            if cls.name == name:
                return cls
        raise KeyError(name)

    for cls in classes:
        ie_name = None
        ie_expr = _class_attr(cls, "_IE_NAME")
        if ie_expr is not None:
            ie_name = _const_str(ie_expr)
        if ie_name is None:
            ie_name = cls.name[:-2] if cls.name.endswith("IE") else cls.name
        rec = ExtractorRecord(module=module, cls=cls.name, ie_name=ie_name)
        rec.class_consts = merged_consts(cls, {cls.name})
        for name, expr in resolver.module_consts.get(module, {}).items():
            if isinstance(expr, ast.Constant) and isinstance(expr.value, str) and len(expr.value) <= 256:
                rec.module_string_consts.setdefault(name, expr.value)

        working = _class_attr(cls, "_WORKING")
        if working is not None:
            val = working if isinstance(working, ast.Constant) else None
            if val is not None and val.value is False:
                rec.working = False

        for attr in ("_TESTS", "_TEST"):
            tests = _class_attr(cls, attr)
            if tests is not None:
                rec.test_url = _first_test_url(tests)
                if rec.test_url:
                    break

        fn = None
        for stmt in cls.body:
            if isinstance(stmt, ast.FunctionDef) and stmt.name == "_real_extract":
                fn = stmt
                break
        if fn is not None:
            rec.has_real_extract = True
            _analyze_real_extract(fn, rec)

        expr = module_class_urls.get((module, cls.name))
        if expr is not None:
            resolved = resolver.resolve(expr, module)
            if resolved is not None:
                rec.valid_url = resolved
            else:
                rec.valid_url_note = "unresolvable"
        else:
            rec.valid_url_note = "inherited"
        out.append(rec)
    return out


def classify(rec: ExtractorRecord) -> None:
    """Populates classification + classify_reason (structural text, no site names)."""
    if rec.parse_error:
        rec.classification, rec.classify_reason = OTHER, "module parse error"
        return
    if not rec.working:
        rec.classification, rec.classify_reason = OTHER, "marked not working"
        return
    if not rec.has_real_extract:
        rec.classification, rec.classify_reason = OTHER, "no own _real_extract"
        return
    if rec.valid_url is None:
        rec.classification, rec.classify_reason = OTHER, "_VALID_URL not statically resolvable"
        return
    if rec.post:
        rec.classification, rec.classify_reason = POST_API, "sends request body (data=)"
        return
    if rec.complex_tokens:
        rec.classification, rec.classify_reason = JS_COMPLEX, "challenge/signature tokens"
        return
    if rec.loc > SIMPLE_LOC_CAP:
        rec.classification, rec.classify_reason = JS_COMPLEX, "_real_extract over %d LOC" % SIMPLE_LOC_CAP
        return
    if not rec.download_calls:
        rec.classification, rec.classify_reason = OTHER, "no direct download call"
        return
    if len(rec.download_calls) > 1:
        rec.classification, rec.classify_reason = JS_COMPLEX, "multiple download calls"
        return
    if rec.download_calls[0].func not in SIMPLE_DOWNLOAD_FUNCS:
        rec.classification, rec.classify_reason = JS_COMPLEX, "non-simple downloader (%s)" % rec.download_calls[0].func
        return
    if rec.traversal_calls > _MAX_TRAVERSAL_FOR_SIMPLE:
        rec.classification, rec.classify_reason = JS_COMPLEX, "heavy nested traversal"
        return
    rec.classification, rec.classify_reason = SIMPLE, "single GET, id-templated, plain access"


def analyze_tree(root: str) -> list[ExtractorRecord]:
    """Analyze every extractor module under root (root = yt_dlp source dir)."""
    extractor_dir = _find_extractor_dir(root)
    module_consts: dict[str, dict[str, ast.AST]] = {}
    parsed: list[tuple[str, ast.Module]] = []
    records: list[ExtractorRecord] = []
    for name in sorted(os.listdir(extractor_dir)):
        if not name.endswith(".py") or name.startswith("__"):
            continue
        path = os.path.join(extractor_dir, name)
        try:
            with open(path, encoding="utf-8") as fh:
                tree = ast.parse(fh.read(), filename=name)
        except (SyntaxError, UnicodeDecodeError, ValueError) as exc:
            records.append(ExtractorRecord(module=name, cls="<module>", ie_name=name,
                                           parse_error=str(exc)[:120]))
            continue
        parsed.append((name, tree))
        module_consts[name] = _module_consts(tree)

    resolver = _StaticResolver({}, module_consts)
    for name, tree in parsed:
        records.extend(analyze_module(name, tree, resolver))
    for rec in records:
        classify(rec)
    return records


def _find_extractor_dir(root: str) -> str:
    candidate = os.path.join(root, "yt_dlp", "extractors")
    if os.path.isdir(candidate):
        return candidate
    candidate = os.path.join(root, "yt_dlp", "extractor")
    if os.path.isdir(candidate):
        return candidate
    raise FileNotFoundError("no yt_dlp/extractor{s} directory under %s" % root)
