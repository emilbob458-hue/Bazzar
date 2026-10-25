#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};

in vec4 vertexColor;
in vec2 localPos;
flat in vec2 halfSize;
flat in float radius;
flat in float mode;

out vec4 fragColor;

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    float r = radius;
    if (mode > 2.5 && localPos.x > 0.0) {
        r = 0.0;
    }
    float d = sdRoundedBox(localPos, halfSize, r);
    float aa = max(fwidth(d) * 0.7, 0.0001);
    float alpha;
    if (mode > 0.5 && mode < 1.5) {
        float core = (1.0 - smoothstep(-aa, aa, d)) * smoothstep(-1.0 - aa, -1.0 + aa, d);
        float t = clamp(d / 3.5, 0.0, 1.0);
        float glow = (1.0 - t) * (1.0 - t) * 0.55 * smoothstep(-aa, aa, d);
        alpha = max(core, glow);
    } else {
        alpha = 1.0 - smoothstep(-aa, aa, d);
    }
    vec4 color = vec4(vertexColor.rgb, vertexColor.a * alpha);
    if (color.a < 0.004) {
        discard;
    }
    fragColor = color * ColorModulator;
}
