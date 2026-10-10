package com.opspilot.action;

/**
 * 结构化只读行动契约的一条（ADRs 0017）。
 *
 * <p>契约语义：**只读**。网关只描述"该跑什么来定位"，自己从不执行、也不推导写操作——
 * ADR-0013 的「只读、零写、非侵入式可信基座」在数据面的落地形态。
 * 每条命令都从**被本次检索引用到的 chunk 原文**里逐字抽出（见 {@link ReadOnlyActionExtractor}），
 * 由 {@link ReadOnlyCommandPolicy} 判过"读"才上线；因此契约不引入任何新的信息面——
 * 命令文本本就是已下发的参考内容，`ref`/`breadcrumb`/`service` 与 `refs` 同源，
 * 租户与密级过滤在检索层已完成，这里不再设闸（也没有可设的）。
 *
 * @param n          1 基连续序号（跨 chunk 重编号，客户端按顺序读）
 * @param step       该命令所属小节标题（如「第一步：确认是数据库慢还是连接池满」）
 * @param command    可复制执行的一条只读命令（已合并续行、剥掉围栏）
 * @param lang       原文围栏语言（bash/sql/…；空串=未标注）
 * @param ref        来源 chunkId（与 refs 的 chunkId 同一命名空间，可反查全文）
 * @param breadcrumb 来源 chunk 的完整面包屑（doc &gt; 段 &gt; 小节）
 * @param service    来源服务名
 */
public record Action(
        int n,
        String step,
        String command,
        String lang,
        String ref,
        String breadcrumb,
        String service
) {
}
