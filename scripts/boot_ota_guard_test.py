"""Targeted boot/OTA state and cleanup fixtures; no mounts or device changes."""
import hashlib, os, pathlib, shutil, subprocess, tempfile

root=pathlib.Path(__file__).resolve().parents[1]
shell=shutil.which('sh') or r'C:\Program Files\Git\usr\bin\sh.exe'
helper=(root/'common/boot_ota_guard.sh').as_posix()
with tempfile.TemporaryDirectory() as directory:
    module=pathlib.Path(directory)
    config=module/'config'; config.mkdir()
    (module/'module.prop').write_text('id=LuoShu\n')
    (config/'active_font.conf').write_text('Demo\n')
    (config/'boot-ota-guard.conf').write_text('schema=ziyu-boot-ota-guard-v1\nunfinished_boots=0\nfingerprint=rom-a\n')
    env=dict(os.environ, ZIYU_BOOT_OTA_DISABLE_WORKER='1', ZIYU_BOOT_OTA_TEST_FINGERPRINT='rom-a', ZIYU_BOOT_OTA_TEST_BOOT_COMPLETED='0')
    env['PATH']=str(pathlib.Path(shell).parent)+os.pathsep+env.get('PATH','')
    def run(action,boot,expected):
        result=subprocess.run([shell,helper,action,module.as_posix()],env=dict(env,ZIYU_BOOT_OTA_TEST_BOOT_ID=boot),capture_output=True,text=True,timeout=15)
        print(action,boot,'rc',result.returncode)
        assert result.returncode==expected,(result.stdout,result.stderr,expected)
        return result.stdout
    run('hook','boot-1',0)
    run('hook','boot-1',0)
    run('hook','boot-2',0)
    run('hook','boot-3',10)
    assert (module/'disable').is_file()
    token=(module/'disable').read_text()
    (module/'disable').unlink()
    run('hook','boot-3',10)
    assert (module/'disable').read_text()==token
    status=run('status','boot-3',0)
    assert 'safe_mode=1' in status and 'current_boot_blocked=1' in status
    run('recover','boot-3',0)
    assert not (module/'disable').exists()
    (module/'disable').write_text('manual\n')
    run('recover','boot-3',0)
    assert (module/'disable').read_text()=='manual\n'
    print('Main-agent independent boot guard review PASS')

with tempfile.TemporaryDirectory() as directory:
    module=pathlib.Path(directory); config=module/'config'; config.mkdir()
    (module/'module.prop').write_text('id=LuoShu\n')
    (config/'active_font.conf').write_text('Demo\n')
    (config/'boot-ota-guard.conf').write_text('schema=ziyu-boot-ota-guard-v1\nunfinished_boots=0\nfingerprint=rom-a\n')
    payload=module/'.luoshu-payload'; payload.mkdir(); (payload/'sentinel').write_text('keep-old-generation')
    env=dict(os.environ, ZIYU_BOOT_OTA_DISABLE_WORKER='1', ZIYU_BOOT_OTA_TEST_FINGERPRINT='rom-b', ZIYU_BOOT_OTA_TEST_BOOT_COMPLETED='0')
    env['PATH']=str(pathlib.Path(shell).parent)+os.pathsep+env.get('PATH','')
    def call(action,boot='ota-1',expected=0,*extra):
        result=subprocess.run([shell,helper,action,module.as_posix(),*extra],env=dict(env,ZIYU_BOOT_OTA_TEST_BOOT_ID=boot),capture_output=True,text=True,timeout=25)
        print(action,boot,'rc',result.returncode,flush=True)
        assert result.returncode==expected,(result.stdout,result.stderr,expected)
        return result.stdout
    # Legacy property-only metadata establishes a baseline, not an OTA hold.
    call('hook','bootstrap',0)
    assert not (config/'boot-ota-pending.conf').exists()
    assert not (module/'disable').exists()
    assert (payload/'sentinel').read_text()=='keep-old-generation'
    # An actual transition requires a previously observed system-file identity.
    prior_identity='system-file-v2:'+hashlib.sha256(b'rom-a\n').hexdigest()
    (config/'boot-ota-guard.conf').write_text(
        'schema=ziyu-boot-ota-guard-v1\nunfinished_boots=0\nfingerprint=rom-a\n'
        f'rom_identity={prior_identity}\n')
    call('hook',expected=12)
    assert (payload/'sentinel').read_text()=='keep-old-generation'
    call('allow-apply', 'ota-1',21,'Demo')
    call('allow-apply', 'ota-1',0,'default')
    (config/'device_font_inventory.json').write_text('{"device":{"buildFingerprint":"rom-b"},"slots":{}}')
    (config/'stock_inventory_scan_pending').unlink()
    call('scan-complete')
    call('allow-apply','ota-1',0,'Demo')
    stage=module/'.luoshu-payload-next'; stage.mkdir()
    marker='schema=luoshu-next-payload-v1\nfont=Demo\nrequestId=review-next-1\n'
    (stage/'.luoshu-next-transaction.conf').write_text(marker)
    (config/'font-payload-next.conf').write_text('state=prepared\nfont=Demo\nrequestId=review-next-1\n')
    call('record-apply','ota-1',0,'Demo')
    assert not (module/'disable').exists()
    assert 'next_generation_ready=1' in call('status')
    assert (config/'boot-ota-pending.conf').exists()
    call('apply-verified', 'ota-1',1)
    call('loaded-generation-identity','ota-1',1,'Demo')
    call('hook','ota-2',0)
    # Model actual transaction activation: the prepared marker becomes live.
    (config/'font-payload-next.conf').unlink()
    shutil.copy2(stage/'.luoshu-next-transaction.conf',payload/'.luoshu-next-transaction.conf')
    (config/'device-font-load-verification.conf').write_text('state=verified\nactiveFont=Demo\nbootId=ota-1\n')
    call('apply-verified', 'ota-2',1)
    (config/'device-font-load-verification.conf').write_text('state=verified\nactiveFont=Demo\nbootId=ota-2\ngenerationIdentity=old-generation\n')
    call('apply-verified', 'ota-2',1)
    generation=call('loaded-generation-identity','ota-2',0,'Demo').strip()
    (config/'device-font-load-verification.conf').write_text(f'state=verified\nactiveFont=Demo\nbootId=ota-2\ngenerationIdentity={generation}\n')
    call('apply-verified', 'ota-2',0)
    assert not (config/'boot-ota-pending.conf').exists()
    print('Main-agent independent OTA generation review PASS',flush=True)

with tempfile.TemporaryDirectory() as directory:
    work=pathlib.Path(directory); proc=work/'proc'; record=proc/'123'; record.mkdir(parents=True)
    tail=['S']+['0']*18+['123']+['0']*3
    (record/'stat').write_text('123 (fixture task) '+' '.join(tail)+'\n')
    task='ziyu-stock-rescan-fixture'; supervisor='/data/adb/modules/LuoShu/common/task_scope.py'
    (record/'cmdline').write_bytes(b'python\0'+supervisor.encode()+b'\0--task\0'+task.encode()+b'\0')
    env=dict(os.environ, ZIYU_BOOT_OTA_TEST_PROC_ROOT=proc.as_posix())
    env['PATH']=str(pathlib.Path(shell).parent)+os.pathsep+env.get('PATH','')
    def identity(expected_task=task, expected_start='123'):
        return subprocess.run([shell,'-c','. "$1"; _zbg_worker_identity 123 "$2" "$3" "$4"',
                               'guard-fixture',helper,expected_task,expected_start,supervisor],env=env,
                              capture_output=True,timeout=15).returncode
    assert identity()==0
    assert identity(task+'-other')!=0
    assert identity(expected_start='124')!=0
    print('Supervisor exact argv and PID/start identity PASS',flush=True)

    module=work/'module'; (module/'config').mkdir(parents=True)
    lockroot=work/'locks'; lock=lockroot/'.ziyu-luoshu-stock-rescan.lock'; lock.mkdir(parents=True)
    (module/'config/boot-ota-pending.conf').write_text('schema=ziyu-ota-pending-v1\nscan_complete=0\nfingerprint=rom-b\n')
    (lock/'state').write_text(f'module={module.as_posix()}\nboot_id=previous-boot\ntask={task}\n')
    env.update(ZIYU_BOOT_OTA_TEST_LOCK_ROOT=lockroot.as_posix(), ZIYU_BOOT_OTA_TEST_BOOT_ID='new-boot')
    result=subprocess.run([shell,helper,'status',module.as_posix()],env=env,capture_output=True,text=True,timeout=20)
    assert result.returncode==0 and 'scan_state=error' in result.stdout, result.stdout
    (lock/'state').write_text(f'module=/unrelated/module\nboot_id=new-boot\ntask={task}\n')
    result=subprocess.run([shell,helper,'uninstall-cleanup',module.as_posix()],env=env,capture_output=True,text=True,timeout=20)
    assert result.returncode!=0 and (lock/'state').exists(),result.stdout
    (lock/'state').write_text(f'module={module.as_posix()}\nboot_id=new-boot\ntask={task}\nsupervisor_script={module.as_posix()}/common/task_scope.py\nfinished=1\n')
    result=subprocess.run([shell,helper,'uninstall-cleanup',module.as_posix()],env=env,capture_output=True,text=True,timeout=20)
    assert result.returncode==0 and not lock.exists(),result.stdout
    print('Stale scan and owned cleanup contracts PASS',flush=True)

    state=work/'private-state'; state.mkdir()
    target=module/'system'; target.mkdir()
    mounts=state/'module-view.mounts'; mounts.write_text(target.as_posix()+'\n')
    env.update(MODDIR=module.as_posix(),MODULE_DIR=module.as_posix(),LUOSHU_PRIVATE_STATE_ROOT=state.as_posix())
    result=subprocess.run([shell,'-c','. "$1"; _luoshu_private_is_mountpoint() { return 0; }; _luoshu_private_umount_cmd() { return 1; }; luoshu_private_unmount_module_view "$2"',
                           'cleanup-fixture',(root/'common/private_payload.sh').as_posix(),module.as_posix()],
                          env=env,capture_output=True,text=True,timeout=20)
    assert result.returncode!=0 and mounts.read_text().strip()==target.as_posix(),result.stderr
    print('Failed unmount preserves evidence PASS',flush=True)
