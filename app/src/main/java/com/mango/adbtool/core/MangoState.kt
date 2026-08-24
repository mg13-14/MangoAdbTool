package com.mango.adbtool.core
enum class MangoState(val label: String, val emoji: String, val desc: String) {
    OFFLINE("小芒果在睡觉", "💤", "点击下方开启配对，开始自动化流程"),
    PAIRING("正在与系统握手…", "🤝", "正在验证配对码，别着急，马上就好"),
    SCANNING("正在扫描服务端口…", "📡", "配对成功！正在全盘扫描无线调试服务端口"),
    STARTING("正在唤醒提权服务…", "⏳", "端口已找到，正在拉起 shell 权限服务"),
    RUNNING("提权服务运行中", "🥭", "一切就绪！尽情折腾你的设备吧 🎉"),
    FAILED("启动失败", "🍑", "流程中断了，检查下网络或配对码是否过期")
}
