#version 330
// Полноэкранный квад из 6 вершин без VBO (DefaultVertexFormat.EMPTY, draw(0, 6)).
out vec2 texCoord;

const vec2 POS[6] = vec2[](
    vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
    vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0)
);

void main() {
    vec2 p = POS[gl_VertexID];
    texCoord = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
