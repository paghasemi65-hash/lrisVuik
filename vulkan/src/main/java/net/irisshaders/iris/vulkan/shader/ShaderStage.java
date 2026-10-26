package net.irisshaders.iris.vulkan.shader;

import static org.lwjgl.util.shaderc.Shaderc.*;
public enum ShaderStage {
    VERTEX(shaderc_vertex_shader), FRAGMENT(shaderc_fragment_shader), COMPUTE(shaderc_compute_shader);
    public final int shadercKind;
    ShaderStage(int kind) { this.shadercKind = kind; }
}
