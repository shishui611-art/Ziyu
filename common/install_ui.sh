#!/system/bin/sh
# Root managers own the window. Use short, static text; no ANSI cursor controls,
# fake percentages, subprocess spinners or background animation jobs.
luoshu_install_header() {
    ui_print ''
    ui_print '  字域'
    ui_print "  ${1#v} 字体管理器"
    ui_print '  ────────────────────────'
    ui_print '  ColorOS For You'
    ui_print '  保留 Emoji、图标和代码等宽字体'
}
luoshu_install_step() {
    ui_print ''
    ui_print "  [$1/4] $2"
    ui_print '  ────────────────────────'
}
luoshu_install_complete() {
    ui_print ''
    ui_print '  ────────────────────────'
    ui_print '  ✓ 模块安装完成'
    ui_print '  请完整重启手机，再进入字域'
    ui_print '  字体任务按需运行，结束即退出'
    ui_print '  ────────────────────────'
    ui_print ''
}
