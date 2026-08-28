"""RRF 融合单测（与 Java RrfFusion 对齐）。"""

from ai_example.samples.hybrid_rag import rrf_fuse


def test_rrf_fuse_prefers_overlap():
    fused = rrf_fuse([0, 1, 2], [1, 3], k=60, limit=4)
    assert fused[0][0] == 1
    assert fused[0][2] == 2
    assert fused[0][3] == 1
