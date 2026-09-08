package com.feike.ai.production.sse;

/**
 * 一次 run 的生命周期状态，与事件流一起持久化。
 * <p>
 * 存在的意义：客户端断线后无法自己区分「服务端已经跑完」和「跑到一半断了」。
 * 把状态放在服务端（Redis）后，重连时可以明确回答这个问题，而不是靠猜。
 */
public enum RunState {

    /** 已登记但还没写出任何业务事件。 */
    PENDING,

    /** 正在产出事件。 */
    STREAMING,

    /** 正常收尾，已写出 done。 */
    DONE,

    /** 异常收尾，已写出 error。 */
    ERROR,

    /** 客户端主动取消或连接断开导致上游被中断。 */
    CANCELLED;

    /**
     * @return 是否已到终态；终态的 run 重连时直接回放到底并关闭
     */
    public boolean terminal() {
        return this == DONE || this == ERROR || this == CANCELLED;
    }
}
