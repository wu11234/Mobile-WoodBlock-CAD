precision mediump float;

varying vec3 v_Normal;
varying vec3 v_FragPos;
varying vec3 v_Color;

uniform vec3 u_LightDir;
uniform int u_IsWireframe; // 1 = 绘制工程结构轮廓线, 0 = 绘制实体表面
uniform int u_UseVertexColor; // 1 = 使用真实相机采样色彩 (神经网络表面模式), 0 = 使用 CAD 哑光结构白模

void main() {
    if (u_IsWireframe == 1) {
        // 工程图结构特征线：高对比深墨青色
        gl_FragColor = vec4(0.12, 0.16, 0.22, 1.0);
        return;
    }

    vec3 norm = normalize(v_Normal);
    vec3 lightDir = normalize(u_LightDir);
    vec3 fillLightDir = normalize(vec3(-0.4, -0.6, -0.5));
    
    // 基础漫反射与环境光
    float diff1 = max(dot(norm, lightDir), 0.0) * 0.55;
    float diff2 = max(dot(norm, fillLightDir), 0.0) * 0.18;
    float ambient = 0.35;
    
    vec3 baseColor;
    if (u_UseVertexColor == 1) {
        baseColor = v_Color;
    } else {
        baseColor = vec3(0.88, 0.90, 0.93);
    }
    
    vec3 viewDir = normalize(-v_FragPos);
    vec3 halfDir = normalize(lightDir + viewDir);
    float spec = pow(max(dot(norm, halfDir), 0.0), 24.0) * 0.20;
    
    vec3 litColor = baseColor * (ambient + diff1 + diff2) + vec3(spec);
    gl_FragColor = vec4(litColor, 1.0);
}
