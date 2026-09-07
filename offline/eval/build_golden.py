"""生成 Golden Dataset：50 组标注样本（25 精确错误码 + 25 口语化语义）。

精确样本的 ground truth 从 chunks.jsonl 自动派生（含该错误码的文档集合），
语义样本为手工构造的口语化描述 → 期望文档。与合成语料同源，保证可对齐。

运行目录：offline/。所有路径经 resolve 校验，禁止 .. 且限定在本目录内。
"""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

ROOT = Path.cwd().resolve()


def _within_root(rel: str) -> Path:
    """解析相对路径并强制其落在当前工作目录内，拒绝任何 .. 越界。"""
    if ".." in Path(rel).parts:
        raise ValueError(f"路径含 .. 越界: {rel}")
    p = (ROOT / rel).resolve()
    if ROOT not in p.parents and p != ROOT:
        raise ValueError(f"路径越出允许目录: {rel}")
    return p


# 25 个精确错误码（与注册表一致）
EXACT_CODES = [
    "50012_DB_TIMEOUT", "50013_DB_DEADLOCK", "50021_REDIS_TIMEOUT", "50022_REDIS_CONN_REFUSED",
    "50031_MQ_CONSUME_LAG", "50032_MQ_SEND_FAILED", "50041_PAY_GATEWAY_502", "50042_PAY_SIGN_INVALID",
    "40901_ORDER_STATE_CONFLICT", "40902_INVENTORY_INSUFFICIENT", "42901_RATE_LIMIT_EXCEEDED",
    "50051_ES_INDEX_MISSING", "50061_OSS_UPLOAD_DENIED", "50071_CONFIG_CENTER_UNREACHABLE",
    "50081_K8S_POD_OOMKILLED", "50082_K8S_NODE_NOT_READY", "50091_JVM_GC_PAUSE",
    "50092_THREAD_POOL_EXHAUSTED", "50101_SLOW_SQL_DETECTED", "50111_CERT_EXPIRING",
    "50121_DNS_RESOLUTION_FAILED", "50131_NTP_DRIFT", "50141_CONNECTION_POOL_EXHAUSTED",
    "50151_FEIGN_TIMEOUT", "50161_SENTINEL_BLOCKED",
]

# 25 个口语化语义查询 → 期望文档（与语料主题词重叠，mock 词法向量可召回）
SEMANTIC = [
    ("下单一直超时页面转圈，数据库是不是扛不住了", ["rb-001", "pm-001"]),
    ("连接池被占满，获取连接等待超时", ["rb-001", "pm-001", "rb-012"]),
    ("库存扣减的时候事务互相卡住回滚了", ["rb-002", "pm-002"]),
    ("购物车加载不出来，缓存服务响应太慢", ["rb-003", "pm-003"]),
    ("登录失败，连不上会话存储的 Redis", ["rb-003", "pm-004"]),
    ("支付回调处理特别慢，消息堆积了几十万", ["rb-004", "pm-005"]),
    ("订单事件发不出去，消息队列投递失败", ["rb-004", "pm-006"]),
    ("第三方支付渠道挂了，下单付款全部报错", ["rb-005", "pm-007"]),
    ("回调签名验证不通过，密钥版本对不上", ["rb-005", "pm-008"]),
    ("订单取消和支付撞车了，状态机报错", ["rb-006", "pm-009"]),
    ("明明显示有货，提交订单却说库存不足", ["rb-007", "pm-002"]),
    ("大促流量太大，接口被限流拒绝请求", ["rb-008", "pm-015"]),
    ("搜索商品报错，说索引不存在", ["rb-013"]),
    ("用户头像上传失败，对象存储拒绝访问", ["rb-013"]),
    ("配置中心连不上，服务拉不到最新配置", ["pm-015", "pm-004", "pm-014"]),
    ("发券服务容器被杀掉了，反复重启", ["rb-009", "pm-010"]),
    ("K8s 节点掉线了，Pod 调度不上去", ["rb-010", "pm-011"]),
    ("接口每隔几秒卡一下，像是垃圾回收停顿", ["rb-011", "pm-012"]),
    ("异步任务全部排队，线程池满了拒绝执行", ["rb-012", "pm-013"]),
    ("内部域名解析不了，服务之间调不通", ["pm-014"]),
    ("机器时间不准，签名被判定为未来时间", ["rb-010", "pm-011"]),
    ("数据库连接泄漏，池子耗尽要重启", ["rb-001", "pm-001"]),
    ("调用下游服务超时，Feign 读超时", ["rb-005", "pm-007", "pm-013"]),
    ("HTTPS 握手失败，证书快要过期了", ["rb-014"]),
    ("热点商品加购被熔断保护挡住了", ["rb-008", "pm-003"]),
]


def main() -> int:
    code2docs = defaultdict(set)
    with _within_root("corpus/chunks.jsonl").open(encoding="utf-8") as fh:
        for line in fh:
            c = json.loads(line)
            for code in c["metadata"]["error_codes"]:
                code2docs[code].add(c["doc_id"])

    samples = []
    for code in EXACT_CODES:
        docs = sorted(code2docs.get(code, []))
        assert docs, f"错误码无对应文档: {code}"
        samples.append({"id": f"exact-{code}", "type": "exact", "query": code,
                        "expected_docs": docs, "auth_level": 3})
    for i, (q, docs) in enumerate(SEMANTIC):
        samples.append({"id": f"sem-{i+1:02d}", "type": "semantic", "query": q,
                        "expected_docs": docs, "auth_level": 3})

    with _within_root("eval/golden_dataset.jsonl").open("w", encoding="utf-8") as fh:
        for s in samples:
            fh.write(json.dumps(s, ensure_ascii=False) + "\n")
    print(f"OK golden={len(samples)} (exact={len(EXACT_CODES)} semantic={len(SEMANTIC)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
