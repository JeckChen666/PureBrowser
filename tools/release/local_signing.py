#!/usr/bin/env python3
"""Local Release signing. No password arguments, logs, plaintext files or repository keys.
macOS Keychain is accessed via Security.framework, not `security -w <password>` argv.
"""
import argparse, ctypes as C, hashlib, os, secrets, subprocess, sys
from pathlib import Path

SERVICE="PureBrowser.ReleaseSigning"
ACCOUNT="purebrowser-release"
DEFAULT_STORE=Path.home()/".config/purebrowser/signing/release.p12"

class Keychain:
    def __init__(self):
        if sys.platform!="darwin": raise RuntimeError("Use PB_SIGNING_* environment values outside macOS")
        self.cf=C.CDLL("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
        self.sec=C.CDLL("/System/Library/Frameworks/Security.framework/Security")
        self.cf.CFStringCreateWithCString.restype=C.c_void_p
        self.cf.CFStringCreateWithCString.argtypes=[C.c_void_p,C.c_char_p,C.c_uint32]
        self.cf.CFDataCreate.restype=C.c_void_p;self.cf.CFDataCreate.argtypes=[C.c_void_p,C.c_void_p,C.c_long]
        self.cf.CFDictionaryCreate.restype=C.c_void_p
        self.cf.CFDictionaryCreate.argtypes=[C.c_void_p,C.c_void_p,C.c_void_p,C.c_long,C.c_void_p,C.c_void_p]
        self.cf.CFDataGetLength.argtypes=[C.c_void_p];self.cf.CFDataGetLength.restype=C.c_long
        self.cf.CFDataGetBytePtr.argtypes=[C.c_void_p];self.cf.CFDataGetBytePtr.restype=C.c_void_p
        self.cf.CFRelease.argtypes=[C.c_void_p]
        self.sec.SecItemCopyMatching.argtypes=[C.c_void_p,C.POINTER(C.c_void_p)];self.sec.SecItemCopyMatching.restype=C.c_int32
        self.sec.SecItemAdd.argtypes=[C.c_void_p,C.c_void_p];self.sec.SecItemAdd.restype=C.c_int32
    def const(self,name): return C.c_void_p.in_dll(self.sec,name).value
    def string(self,text): return self.cf.CFStringCreateWithCString(None,text.encode(),0x08000100)
    def dictionary(self,items):
        keys=(C.c_void_p*len(items))(*(k for k,v in items));values=(C.c_void_p*len(items))(*(v for k,v in items))
        k=C.addressof(C.c_byte.in_dll(self.cf,"kCFTypeDictionaryKeyCallBacks"))
        v=C.addressof(C.c_byte.in_dll(self.cf,"kCFTypeDictionaryValueCallBacks"))
        return self.cf.CFDictionaryCreate(None,keys,values,len(items),k,v)
    def query(self):
        service=self.string(SERVICE);account=self.string(ACCOUNT)
        return [(self.const("kSecClass"),self.const("kSecClassGenericPassword")),
                (self.const("kSecAttrService"),service),(self.const("kSecAttrAccount"),account)], [service,account]
    def read(self):
        items,owned=self.query();items.append((self.const("kSecReturnData"),C.c_void_p.in_dll(self.cf,"kCFBooleanTrue").value))
        query=self.dictionary(items);result=C.c_void_p()
        try:
            status=self.sec.SecItemCopyMatching(query,C.byref(result))
            if status==-25300:return None
            if status!=0:raise RuntimeError(f"Keychain read failed ({status}); no signing fallback")
            return C.string_at(self.cf.CFDataGetBytePtr(result),self.cf.CFDataGetLength(result)).decode()
        finally:
            if result.value:self.cf.CFRelease(result)
            self.cf.CFRelease(query)
            for obj in owned:self.cf.CFRelease(obj)
    def add(self,password):
        items,owned=self.query();raw=password.encode();buffer=C.create_string_buffer(raw)
        data=self.cf.CFDataCreate(None,C.cast(buffer,C.c_void_p),len(raw));owned.append(data)
        items.append((self.const("kSecValueData"),data))
        items.append((self.const("kSecAttrSynchronizable"),C.c_void_p.in_dll(self.cf,"kCFBooleanFalse").value))
        query=self.dictionary(items)
        try:
            status=self.sec.SecItemAdd(query,None)
            if status!=0:raise RuntimeError(f"Keychain write failed ({status}); no plaintext fallback")
        finally:
            self.cf.CFRelease(query)
            for obj in owned:self.cf.CFRelease(obj)

def main():
    parser=argparse.ArgumentParser();parser.add_argument("command",choices=["init","build","certificate"])
    parser.add_argument("--store",type=Path,default=DEFAULT_STORE);parser.add_argument("--instrumented",action="store_true");args=parser.parse_args()
    repo=Path(__file__).resolve().parents[2];store=args.store.expanduser().resolve()
    if repo==store or repo in store.parents: raise RuntimeError("Signing keys must be outside the repository")
    os.umask(0o077)
    kc=Keychain();password=kc.read()
    if args.command=="init":
        if store.exists():
            if password is None:raise RuntimeError("Existing keystore has no matching Keychain entry; will not overwrite")
            print("Existing Release identity retained.")
        else:
            store.parent.mkdir(parents=True,exist_ok=True);store.parent.chmod(0o700)
            if password is None:password=secrets.token_urlsafe(36);kc.add(password)
            temp=store.with_name("release-pending-"+secrets.token_hex(8)+".p12")
            env=dict(os.environ,PB_SIGNING_STORE_PASSWORD=password,PB_SIGNING_KEY_PASSWORD=password)
            try:
                subprocess.run(["keytool","-genkeypair","-keystore",str(temp),"-storetype","PKCS12",
                    "-storepass:env","PB_SIGNING_STORE_PASSWORD","-keypass:env","PB_SIGNING_KEY_PASSWORD",
                    "-alias",ACCOUNT,"-keyalg","RSA","-keysize","4096","-validity","10000",
                    "-dname","CN=PureBrowser Release,O=PureBrowser"],env=env,check=True)
                temp.chmod(0o600);os.link(temp,store);temp.unlink()
            finally:
                if temp.exists():temp.unlink()
            print("Created local PKCS12 Release identity; password stored only in macOS Keychain.")
    if password is None or not store.is_file():raise RuntimeError("Missing Release identity. Run init locally; no Debug fallback.")
    env=dict(os.environ,PB_SIGNING_STORE_FILE=str(store),PB_SIGNING_STORE_PASSWORD=password,PB_SIGNING_KEY_PASSWORD=password,PB_SIGNING_KEY_ALIAS=ACCOUNT)
    if args.command=="build":
        tasks=[":app:assembleRelease",":app:assembleReleaseAndroidTest","-PreleaseSmoke=true"] if args.instrumented else [":app:assembleRelease"]
        subprocess.run([str(repo/"gradlew"),*tasks,"--no-configuration-cache","--console=plain"],cwd=repo,env=env,check=True)
    if args.command=="certificate":
        der=subprocess.check_output(["keytool","-exportcert","-keystore",str(store),"-storetype","PKCS12","-storepass:env","PB_SIGNING_STORE_PASSWORD","-alias",ACCOUNT],env=env)
        print("Certificate SHA-256:",hashlib.sha256(der).hexdigest())

if __name__=="__main__":
    try:main()
    except Exception as error:
        print(f"Signing action failed: {type(error).__name__}: {error}",file=sys.stderr);sys.exit(1)
