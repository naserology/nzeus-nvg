#version 300 es
precision highp float;

layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTex;

uniform mat4 uTexMatrix;

out vec2 vTex;

void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
}
