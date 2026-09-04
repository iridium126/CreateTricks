// Shared ALLVR node SSBO layout + decode (doc §13 阶段 4a). Single-sourced
// across the traversal / cmdgen / revalidate kernels via #pragma cmi_include.
// 32 B per node:
//   a.x = zCoord(21b biased) | yCoord(21b biased) << 21
//   a.y = xCoord(21b biased) | level(3b) << 21 | flags(8b) << 24
//   a.z = quadStart        (arena quad index)
//   a.w = childPtr         (first of 8 consecutive children; 0 = none, 4c)
//   b.x = quadCount
//   b.y = visibleFrameId   (0 = never/cleared)
//   b.z = lastRequestFrame (4c)
//   b.w = slot             (cubeInfo index, 0 = none)
struct Node {
    uvec4 a;
    uvec4 b;
};
layout(std430, binding = BIND_NODES) buffer NodeBuf {
    Node nodes[];
};

int se21(uint bits) {
    return int(bits << 11) >> 11;
}

bool nodeLive(Node n) {
    uint f = n.a.y >> 24;
    return (f & NODE_FLAG_DEAD) == 0u && (f & NODE_FLAG_HAS_MESH) != 0u;
}

uint nodeLevel(Node n) {
    return (n.a.y >> 21) & 7u;
}

/** Absolute block-space origin of the node's min corner. */
ivec3 nodeAbsOrigin(Node n) {
    ivec3 cubeCoord = ivec3(se21(n.a.y & 0x1FFFFFu),        // x
                            se21(n.a.x & 0x1FFFFFu),        // y
                            se21((n.a.x >> 21) & 0x1FFFFFu)); // z
    return cubeCoord << int(5u + nodeLevel(n));
}
