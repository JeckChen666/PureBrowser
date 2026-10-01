#!/usr/bin/env python3
"""Fail-closed private state backup/restore around destructive UI regressions.
Does not copy Download files, remove system tasks, or uninstall the target application.
Backups belong outside the repository with mode 0700; never log their JSON contents.
"""
import argparse, hashlib, json, os, subprocess, tempfile
from pathlib import Path

PACKAGE = "com.example.purebrowser"
FILES = ["files/browser-state.json", "files/browser-state.json.bak", "files/browser-state.json.new",
         "files/download-state.json", "files/download-state.json.bak", "files/download-state.json.new",
         "shared_prefs/download_records.xml", "shared_prefs/download_preferences.xml"]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["backup", "restore"])
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--directory", type=Path)
    args = parser.parse_args()
    prefix = ["adb", "-s", args.device]
    def run(*command, data=None):
        return subprocess.run(prefix + list(command), input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
    def shell(command): return run("shell", "-T", "run-as", PACKAGE, "sh", "-c", '"' + command + '"')
    assert run("get-state").strip() == b"device", "Device is not ready; abort without restoring"
    assert b"uid=" in run("shell", "run-as", PACKAGE, "id"), "Target app unavailable"
    run("shell", "am", "force-stop", PACKAGE)
    directory = args.directory
    if args.mode == "backup":
        directory = directory or Path(tempfile.mkdtemp(prefix="purebrowser-r2-private-"))
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(directory, 0o700)
        manifest = {}
        for path in FILES:
            state = shell(f"if test -f {path}; then echo present; elif test ! -e {path}; then echo absent; else echo invalid; fi").strip()
            assert state in (b"present", b"absent"), f"Cannot determine state of {path}"
            if state == b"present":
                raw = run("exec-out", "run-as", PACKAGE, "cat", path)
                output = directory / path
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(raw)
                manifest[path] = hashlib.sha256(raw).hexdigest()
            else: manifest[path] = None
        (directory / "manifest.json").write_text(json.dumps(manifest, indent=2))
        print(directory)
    else:
        assert directory is not None
        manifest = json.loads((directory / "manifest.json").read_text())
        assert set(manifest) == set(FILES), "Incomplete or foreign backup"
        for path, digest in manifest.items():
            if digest is None:
                shell(f"rm -f {path}")
                assert shell(f"test ! -e {path} && echo absent").strip() == b"absent"
            else:
                raw = (directory / path).read_bytes()
                assert hashlib.sha256(raw).hexdigest() == digest
                parent = str(Path(path).parent)
                run("shell", "-T", "run-as", PACKAGE, "sh", "-c", f'"mkdir -p {parent}; cat > {path}"', data=raw)
                assert run("exec-out", "run-as", PACKAGE, "cat", path) == raw
        print("Restored original private state byte-for-byte; device files and unrelated tasks untouched.")

if __name__ == "__main__": main()
