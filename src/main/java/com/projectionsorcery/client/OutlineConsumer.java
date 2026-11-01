package com.projectionsorcery.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;

/**
 * Swallows the quads of a model and stores them. The lines are drawn later to avoid buffer state conflicts.
 */
final class OutlineConsumer implements VertexConsumer {
    private final List<float[]> quads = new ArrayList<>();
    private final float[] p = new float[12];
    private int n;

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (n == 4) emit();
        p[n * 3] = x;
        p[n * 3 + 1] = y;
        p[n * 3 + 2] = z;
        n++;
        return this;
    }

    private void emit() {
        quads.add(p.clone());
        n = 0;
    }

    public List<float[]> getQuads() {
        if (n == 4) emit();
        return quads;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        return this;
    }
}