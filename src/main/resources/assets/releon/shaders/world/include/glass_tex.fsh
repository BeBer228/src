#version 330
#extension GL_ARB_separate_shader_objects : require

// Glass Block - textured face mode.
//
// The donor built two RenderLayers with `EndpointTexture`/`end_sky.png` and `end_portal.png`
// scrolling their UVs by a per-frame offset. Both are reproduced here; which texture is bound is a
// runtime decision, and the parallax/scroll is baked into the UVs by the vertex stream, so no extra
// uniform block is needed. Additive blending for the portal layer is a property of the pipeline
// the caller binds.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    // The End textures are a full-bleed nebula; their own alpha is mostly 1, so the per-vertex alpha
    // is what fades the panel towards the block's silhouette.
    vec4 color = vec4(texel.rgb * vertexColor.rgb, texel.a * vertexColor.a);
    if (color.a < 0.004) discard;
    fragColor = color;
}
