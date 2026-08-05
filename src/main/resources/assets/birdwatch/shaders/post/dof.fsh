#version 330

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform DofConfig {
    float FocusDepthRaw;   // 对焦距离的 reversed-Z 深度值
    float FocalMM;         // 焦距(毫米)
    float ApertureF;       // 光圈 F 值
    float FocusDistM;      // 对焦距离(米)
    float Near;
    float Far;
    float ExposureScale;   // 曝光增益(2^EV)
};

in vec2 texCoord;

out vec4 fragColor;

// 24 方向圆盘采样(运行时旋转,打散边缘伪影的规律性)
const vec2 disc[24] = vec2[](
    vec2(0.0, 0.0),
    vec2(0.258819, 0.965926), vec2(0.965926, 0.258819),
    vec2(0.707107, -0.707107), vec2(-0.258819, -0.965926),
    vec2(-0.965926, -0.258819), vec2(-0.707107, 0.707107),
    vec2(0.965926, -0.258819), vec2(0.258819, -0.965926),
    vec2(-0.258819, 0.965926), vec2(0.707107, 0.707107),
    vec2(-0.965926, 0.258819), vec2(0.0, 1.0),
    vec2(1.0, 0.0), vec2(0.0, -1.0), vec2(-1.0, 0.0),
    vec2(0.5, 0.866025), vec2(0.866025, -0.5),
    vec2(-0.5, -0.866025), vec2(-0.866025, 0.5),
    vec2(0.866025, 0.5), vec2(-0.5, 0.866025),
    vec2(-0.866025, -0.5), vec2(0.5, -0.866025)
);

// reversed-Z 深度 → 线性距离(米)
float linearizeDepth(float z) {
    float zndc = 1.0 - 2.0 * z;
    return (2.0 * Near * Far) / (Far + Near - zndc * (Far - Near));
}

void main() {
    float depthRaw = texture(InDepthSampler, texCoord).r;

    // 深度异常防护
    if (isnan(depthRaw) || depthRaw < 0.0 || depthRaw > 1.0) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    float depth = linearizeDepth(depthRaw);
    float focusDepth = linearizeDepth(FocusDepthRaw);

    // 物理弥散圆模型(单位统一为毫米):coc = f²/N × |1/s − 1/sf|
    // f、s、sf 均用毫米;全幅传感器 36mm,像素/毫米 = 画面高度 / 36
    float pxPerMm = InSize.y / 36.0;
    float sMm = depth * 1000.0;
    float sfMm = FocusDistM * 1000.0;
    float cocMm = (FocalMM * FocalMM / ApertureF) * abs(1.0 / sMm - 1.0 / sfMm);
    float coc = clamp(cocMm * pxPerMm, 0.0, 20.0);

    // 焦内保护:弥散圆过小直接输出,保持焦平面与边缘干净
    if (coc < 2.0) {
        fragColor = vec4(texture(InSampler, texCoord).rgb * ExposureScale, 1.0);
        return;
    }

    // 旋转抖动:每像素基于位置旋转圆盘,减少采样规律性
    float angle = fract(sin(dot(floor(texCoord * InSize), vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
    float cosA = cos(angle);
    float sinA = sin(angle);

    vec2 texel = 1.0 / InSize;
    vec4 acc = vec4(0.0);
    float total = 0.0;
    for (int i = 0; i < 24; i++) {
        vec2 d = disc[i];
        vec2 rotated = vec2(d.x * cosA - d.y * sinA, d.x * sinA + d.y * cosA);
        vec2 offset = rotated * coc * texel;
        // 深度感知采样:采样点与当前像素深度差过大(跨过前景/背景边缘)则跳过,
        // 防止背景模糊污染前景边缘(天空虚影的来源)
        float sampleDepthRaw = texture(InDepthSampler, texCoord + offset).r;
        if (isnan(sampleDepthRaw) || sampleDepthRaw < 0.0 || sampleDepthRaw > 1.0) {
            continue;
        }
        float sampleDepth = linearizeDepth(sampleDepthRaw);
        if (abs(sampleDepth - depth) > 8.0) {
            continue;
        }
        float w = i == 0 ? 4.0 : 1.0;
        acc += texture(InSampler, texCoord + offset) * w;
        total += w;
    }
    if (total <= 0.0) {
        fragColor = vec4(texture(InSampler, texCoord).rgb * ExposureScale, 1.0);
        return;
    }
    acc /= total;
    fragColor = vec4(acc.rgb * ExposureScale, 1.0);
}
