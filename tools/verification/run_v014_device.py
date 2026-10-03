#!/usr/bin/env python3
"""Fail-closed named-lab instrumentation; installs existing freshly-built APKs, records and restores settings."""
import argparse,datetime,hashlib,json,re,subprocess,time
from pathlib import Path
from run_v014_regression import parse_results, source_identity, build_current
ROOT=Path(__file__).resolve().parents[2]
PKG='io.github.jeckchen666.purebrowser.debug'
p=argparse.ArgumentParser()
p.add_argument('--device',required=True);p.add_argument('--classes-file',type=Path,required=True)
p.add_argument('--expected',type=int,required=True);p.add_argument('--output',type=Path,required=True)
p.add_argument('--font',type=float);p.add_argument('--density',type=int);p.add_argument('--size')
p.add_argument('--timeout',type=int,default=1800);p.add_argument('--visual',action='store_true')
a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
prefix=['adb','-s',a.device]
def adb(*cmd):return subprocess.check_output(prefix+list(cmd),timeout=120).decode().strip()
avd=adb('emu','avd','name').splitlines()[0]
assert avd in ['PureBrowser_API28_Test','PureBrowser_API36_Test','PureBrowser_API37_ReleaseLab'],'Named disposable lab required'
assert 'mFinished=false' not in adb('shell','dumpsys','activity'),'Existing instrumentation running'
assert adb('shell','getprop','sys.boot_completed')=='1'
source=source_identity();build_current(out);assert source_identity()==source,'Sources changed during build'
apks=[ROOT/'app/build/outputs/apk/debug/app-debug.apk',ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
for f in apks:adb('install','-r',str(f))
classes=json.loads(a.classes_file.read_text())
font=adb('shell','settings','get','system','font_scale')
size=adb('shell','wm','size');density=adb('shell','wm','density')
timeout=adb('shell','settings','get','system','screen_off_timeout')
permissions={}
dump=adb('shell','dumpsys','package',PKG)
api=int(adb('shell','getprop','ro.build.version.sdk'))
required=['android.permission.READ_EXTERNAL_STORAGE','android.permission.WRITE_EXTERNAL_STORAGE'] if api<=28 else (['android.permission.POST_NOTIFICATIONS'] if api>=33 else [])
for name in required:
    assert name in dump,'Required declared permission missing'
    match=re.search(re.escape(name)+r': granted=(true|false)',dump)
    permissions[name]=bool(match and match[1]=='true')
meta={'started_at':datetime.datetime.now().astimezone().isoformat(),'device':a.device,'avd':avd,'api':adb('shell','getprop','ro.build.version.sdk'),
      'source_sha256':source,'apk_sha256':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in apks},'classes':classes,
      'before':{'font':font,'size':size,'density':density,'timeout':timeout,'permissions':permissions},'webview':adb('shell','dumpsys','webviewupdate')}
try:
    for name in permissions:
        adb('shell','pm','grant',PKG,name)
        assert re.search(re.escape(name)+r': granted=true',adb('shell','dumpsys','package',PKG)),'Permission was not granted'
    adb('shell','settings','put','system','screen_off_timeout','2147483647')
    if a.font is not None:adb('shell','settings','put','system','font_scale',str(a.font))
    if a.size:adb('shell','wm','size',a.size)
    if a.density:adb('shell','wm','density',str(a.density))
    adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
    time.sleep(3)
    meta['tested']={'font':adb('shell','settings','get','system','font_scale'),'size':adb('shell','wm','size'),'density':adb('shell','wm','density')}
    cmd=prefix+['shell','am','instrument','-w','-r','-e','class',','.join(classes)]
    for flag in ['resumeFixture','queueFixture','hlsFixture','videoFixture','serviceFixture','crossUidShare','dynamicFixture','v014DisposableProfile']:
        cmd+=['-e',flag,'true']
    if a.visual:cmd+=['-e','v014VisualFixtures','true','-e','v014SourceRevision',source]
    cmd+=[PKG+'.test/androidx.test.runner.AndroidJUnitRunner']
    start=time.monotonic()
    with (out/'instrumentation.txt').open('w') as log:
        result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=a.timeout)
    summary=parse_results((out/'instrumentation.txt').read_text(),a.expected)
    summary.update(elapsed_seconds=round(time.monotonic()-start,3),shell_exit=result.returncode,source_unchanged=source_identity()==source)
    summary['ok']=summary['ok'] and result.returncode==0 and summary['source_unchanged']
    (out/'result.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2),flush=True)
    if not summary['ok']:raise SystemExit(1)
finally:
    for name,was in permissions.items():
        if not was:adb('shell','pm','revoke',PKG,name)
    adb('shell','wm','size',re.search(r'Override size: (\S+)',size)[1] if 'Override size:' in size else 'reset')
    adb('shell','wm','density',re.search(r'Override density: (\S+)',density)[1] if 'Override density:' in density else 'reset')
    for key,value in [('font_scale',font),('screen_off_timeout',timeout)]:
        adb('shell','settings','delete' if value=='null' else 'put','system',key,*([] if value=='null' else [value]))
    meta['restored']={'font':adb('shell','settings','get','system','font_scale'),'size':adb('shell','wm','size'),'density':adb('shell','wm','density')}
    (out/'environment.json').write_text(json.dumps(meta,indent=2)+'\n')
