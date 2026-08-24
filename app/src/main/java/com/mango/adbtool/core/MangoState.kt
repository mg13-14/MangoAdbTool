package com.mango.adbtool.core
enum class MangoState(val label: String, val emoji: String, val desc: String) {
    OFFLINE("小芒果在睡觉", "💤", "点击下方「无线配对」，并在系统中打开配对码界面"),
    SEARCHING_PAIR("正在扫描配对端口…", "📡", "请确保系统已点开配对码界面，小芒果正在全盘雷达扫描"),
    WAITING_FOR_CODE("已发现配对界面！", "✨", "小芒果已自动抓取到端口，请在弹窗中填入配对码"),
    PAIRING("正在与系统握手…", "🤝", "正在验证配对码，别着急，马上就好"),
    SEARCHING_SERVICE("正在扫描服务端口…", "📡", "配对成功！正在寻找真正的服务端口"),
    STARTING("正在唤醒提权服务…", "⏳", "端口已找到，正在拉起 shell 权限服务"),
    RUNNING("提权服务运行中", "🥭", "一切就绪！尽情折腾你的设备吧 🎉"),
    FAILED("启动失败", "🍑", "流程中断了，可能配对码过期或未打开配对界面")
}
