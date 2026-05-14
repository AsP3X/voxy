// Embedded when a shader pack's voxy.json has no voxy_translucent.glsl fragment.
// Uses the same PATCHED vertex layout as opaque LOD (raw light id in interData.y) and
// samples the Minecraft lightmap in the fragment like vanilla/Sodium, so buffer 0 is
// at least consistent lit translucency. Packs should still ship voxy_translucent.glsl for
// reflections and full gbuffer encoding.

layout(binding = 1) uniform sampler2D lightSampler;

layout(location = 0) out vec4 voxyIrisTranslucentOut;

void voxy_emitFragment(VoxyFragmentParameters p) {
    vec4 c = p.sampledColour;
    c.rgb *= p.tinting.rgb;
    vec4 lm = texture(lightSampler, p.lightMap);
    voxyIrisTranslucentOut = vec4(c.rgb * lm.rgb, c.a);
}
