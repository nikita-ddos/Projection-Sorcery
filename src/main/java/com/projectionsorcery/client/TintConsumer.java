package com.projectionsorcery.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Forces a flat color + alpha on everything drawn through it (used for the afterimages). */
final class TintConsumer implements VertexConsumer {
    private final VertexConsumer d;
    private final int r, g, b, a;

    TintConsumer(VertexConsumer delegate, int r, int g, int b, int a) {
        this.d = delegate;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        d.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        d.setColor(r * red / 255, g * green / 255, b * blue / 255, a);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        d.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        d.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        d.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        d.setNormal(x, y, z);
        return this;
    }
}