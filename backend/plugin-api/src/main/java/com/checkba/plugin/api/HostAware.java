package com.checkba.plugin.api;

/**
 * 插件工具类若实现本接口，宿主在无参构造实例化后立即调用 {@link #setHost(PluginHost)}，
 * 注入按插件 id 绑定的宿主门面。不实现则与旧规范完全一致（只有 @Tool）。
 */
public interface HostAware {
    void setHost(PluginHost host);
}
