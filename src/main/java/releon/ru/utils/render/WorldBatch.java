package releon.ru.utils.render;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.OptionalInt;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Render-thread batch: reusable CPU storage, one upload and draw for all submitted triangles. */
public final class WorldBatch {
    private static final RenderPipeline SOLID = IncludeWorldMesh.pipeline("visual/solid", "world_visual_geometry", true, false, false, true);
    private static final RenderPipeline XRAY = IncludeWorldMesh.pipeline("visual/xray", "world_visual_geometry", false, false, false, true);
    private static final RenderPipeline GLOW = IncludeWorldMesh.pipeline("visual/glow", "world_visual_geometry", true, true, false, true);
    private final IncludeWorldMesh gpu = new IncludeWorldMesh();
    private ByteBufferBuilder storage;
    private BufferBuilder vertices;
    private Vec3 origin;
    private final Vector3f right = new Vector3f(), up = new Vector3f();
    private int count;
    private boolean reported;

    public void begin(Camera camera) {
        if (storage == null) storage = new ByteBufferBuilder(65536);
        origin = camera.position();
        right.set(1, 0, 0).rotate(camera.rotation());
        up.set(0, 1, 0).rotate(camera.rotation());
        vertices = new BufferBuilder(storage, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        count = 0;
    }
    private void vertex(double x, double y, double z, float u, float v, int color) {
        vertices.addVertex((float)(x-origin.x), (float)(y-origin.y), (float)(z-origin.z))
                .setUv(u, v).setColor(color);
        count++;
    }
    public void quad(double ax,double ay,double az,double bx,double by,double bz,
                     double cx,double cy,double cz,double dx,double dy,double dz,
                     float u0,float u1,int a,int b) {
        if (count + 6 > 65532) return;
        vertex(ax,ay,az,u0,0,a);vertex(bx,by,bz,u1,0,b);vertex(cx,cy,cz,u1,1,b);
        vertex(ax,ay,az,u0,0,a);vertex(cx,cy,cz,u1,1,b);vertex(dx,dy,dz,u0,1,a);
    }
    public void triangle(double ax,double ay,double az,double bx,double by,double bz,double cx,double cy,double cz,int a,int b,int c) {
        if(count+3>65532)return;
        vertex(ax,ay,az,0,0,a);vertex(bx,by,bz,1,0,b);vertex(cx,cy,cz,.5f,1,c);
    }

    public void billboard(double x,double y,double z,float size,float angle,int color) {
        float cos=(float)Math.cos(angle),sin=(float)Math.sin(angle);
        float rx=(right.x*cos+up.x*sin)*size, ry=(right.y*cos+up.y*sin)*size, rz=(right.z*cos+up.z*sin)*size;
        float ux=(up.x*cos-right.x*sin)*size, uy=(up.y*cos-right.y*sin)*size, uz=(up.z*cos-right.z*sin)*size;
        quad(x-rx-ux,y-ry-uy,z-rz-uz,x+rx-ux,y+ry-uy,z+rz-uz,
                x+rx+ux,y+ry+uy,z+rz+uz,x-rx+ux,y-ry+uy,z-rz+uz,0,1,color,color);
    }
    public void line(double ax,double ay,double az,double bx,double by,double bz,float width,int color) {
        line(ax,ay,az,bx,by,bz,width,color,color);
    }
    public void line(double ax,double ay,double az,double bx,double by,double bz,float width,int ca,int cb) {
        double vx=bx-ax,vy=by-ay,vz=bz-az;
        double ex=(ax+bx)*.5-origin.x,ey=(ay+by)*.5-origin.y,ez=(az+bz)*.5-origin.z;
        double nx=vy*ez-vz*ey,ny=vz*ex-vx*ez,nz=vx*ey-vy*ex;
        double len=Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(len<1e-8)return;
        double s=width*.5/len;nx*=s;ny*=s;nz*=s;
        quad(ax-nx,ay-ny,az-nz,bx-nx,by-ny,bz-nz,bx+nx,by+ny,bz+nz,ax+nx,ay+ny,az+nz,0,1,ca,cb);
    }
    public void box(double x,double y,double z,double w,double h,float thickness,int color) {
        for(int i=0;i<4;i++) {
            double ax=x+((i&1)==0?0:w),az=z+((i&2)==0?0:w);
            line(ax,y,az,ax,y+h,az,thickness,color);
            if((i&1)==0) {line(ax,y,az,ax+w,y,az,thickness,color);line(ax,y+h,az,ax+w,y+h,az,thickness,color);}
            if((i&2)==0) {line(ax,y,az,ax,y,az+w,thickness,color);line(ax,y+h,az,ax,y+h,az+w,thickness,color);}
        }
    }
    /** effect: 0 flat; 1 animated ribbon; 10..19 particle shapes. */
    public void end(boolean depth, boolean additive, float effect, float time, float speed, float glow) {
        MeshData mesh = vertices.build();
        vertices = null;
        if (mesh == null) return;
        try (mesh) {
            var target = Minecraft.getInstance().getMainRenderTarget();
            if (target == null || target.getColorTextureView() == null) return;
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            gpu.upload(encoder, mesh); gpu.effect(encoder, effect, time, speed, glow);
            try (var pass = encoder.createRenderPass(() -> "Include world effect", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(!depth ? XRAY : additive ? GLOW : SOLID);
                gpu.bind(pass, true);
                pass.draw(0, mesh.drawState().vertexCount());
            }
        }
    }
    public void close() {
        gpu.close();
        if (storage != null) { storage.close(); storage = null; }
    }
}
