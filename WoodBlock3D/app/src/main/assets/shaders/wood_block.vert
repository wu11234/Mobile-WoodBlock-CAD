uniform mat4 u_ModelViewProjection;
uniform mat4 u_ModelView;
uniform mat3 u_NormalMatrix;

attribute vec3 a_Position;
attribute vec3 a_Normal;
attribute vec3 a_Color;

varying vec3 v_Normal;
varying vec3 v_FragPos;
varying vec3 v_Color;

void main() {
    v_Normal = normalize(u_NormalMatrix * a_Normal);
    vec4 pos4 = u_ModelView * vec4(a_Position, 1.0);
    v_FragPos = pos4.xyz;
    v_Color = a_Color;
    gl_Position = u_ModelViewProjection * vec4(a_Position, 1.0);
}
