package dev.personal.tv.spider;

/** 插件进程对外的接口。返回值第一个字符：'0' 成功 + 结果，'1' 失败 + 错误信息，'F' 结果太大写在文件里 + 路径 */
interface ISpiderHost {
    String call(String jar, String md5, String key, String api, String ext, String method, in List<String> args);
    int port();
    /** 界面退到后台：把 9978 让出来（后台进程会被系统冻结，占着端口不应答，别的包的插件探测端口会卡 30 秒） */
    void releasePort();
}
