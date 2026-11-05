#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform VisualConfig { vec4 Effect; };
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;
float segment(vec2 p,vec2 a,vec2 b,float w){vec2 d=b-a;return 1.0-smoothstep(w,w+.035,length(p-a-d*clamp(dot(p-a,d)/dot(d,d),0.0,1.0)));}
void main(){
    vec2 uv=texCoord0;vec2 p=uv*2.0-1.0;float mask=1.0;float shine=1.0;
    if(Effect.x>.5&&Effect.x<2.0){
        float edge=pow(abs(p.y),8.0);
        float wave=.5+.5*sin(uv.x*18.0-Effect.y*Effect.z*3.0+p.y*3.0);
        mask=(.18+.6*edge+.22*wave)*(1.0-smoothstep(.86,1.0,abs(p.y)));
        shine=1.0+Effect.w*(edge*.8+pow(wave,8.0)*.35);
    }else if(Effect.x>=20.0){
        float t=Effect.y;float h=uv.y;
        if(Effect.x<20.5){
            float f1=.5+.5*sin(uv.x*18.85+h*12.0-t*5.0);
            float f2=.5+.5*sin(uv.x*31.4-h*20.0+t*8.0);
            float streak=pow(f1*.65+f2*.35,2.5);
            float core=pow(1.0-h,1.3);
            mask=(.3+.7*streak)*core*smoothstep(.0,.05,h);
            shine=1.0+Effect.w*(core*.9+streak*.6);
        }else if(Effect.x<21.5){
            float edge=smoothstep(.55,.93,h)*(1.0-smoothstep(.93,1.0,h));
            float spoke=.75+.25*sin(uv.x*150.8+t*4.0);
            mask=(edge+pow(h,2.5)*.35)*spoke*smoothstep(.0,.08,h);
            shine=1.0+Effect.w*edge*1.2;
        }else{
            float wisp=pow(.5+.5*sin(uv.x*25.13+h*6.0-t*3.0),3.0);
            float fade=pow(1.0-h,1.5);
            mask=fade*(.35+.65*wisp)*smoothstep(.0,.06,h);
            shine=1.0+Effect.w*(fade*.6+wisp*.4);
        }
    }else if(Effect.x>=10.0){
        int shape=int(Effect.x)-10;float d=length(p);
        if(shape==0){p.y=-p.y;float h=pow(p.x*p.x+p.y*p.y-.48,3.0)-p.x*p.x*p.y*p.y*p.y;mask=1.0-smoothstep(-.01,.02,h);}
        else if(shape==1||shape==7){float a=atan(p.y,p.x);float r=.43+.24*cos(a*5.0);mask=1.0-smoothstep(r,r+.055,d);}
        else if(shape==2){mask=max(segment(p,vec2(.35,.55),vec2(-.3,.25),.1),segment(p,vec2(-.3,.25),vec2(.3,-.22),.1));mask=max(mask,max(segment(p,vec2(.3,-.22),vec2(-.35,-.55),.1),segment(p,vec2(0,-.8),vec2(0,.8),.055)));}
        else if(shape==3){float top=-.3+.45*abs(sin(p.x*5.0));mask=(1.0-smoothstep(.7,.75,abs(p.x)))*(1.0-smoothstep(.55,.6,p.y))*smoothstep(top-.04,top,p.y);}
        else if(shape==4){mask=max(segment(p,vec2(.25,-.8),vec2(-.35,.05),.12),max(segment(p,vec2(-.35,.05),vec2(.3,.05),.12),segment(p,vec2(.3,.05),vec2(-.25,.8),.12)));}
        else if(shape==5||shape==6){mask=max(segment(p,vec2(-.65,0),vec2(.65,0),.04),segment(p,vec2(0,-.65),vec2(0,.65),.04));if(shape==5)mask=max(mask,max(segment(p,vec2(-.45,-.45),vec2(.45,.45),.035),segment(p,vec2(-.45,.45),vec2(.45,-.45),.035)));}
        else {mask=exp(-d*d*(shape==9?5.0:4.0))*(1.0-smoothstep(.75,1.0,d));}
        shine=1.0+Effect.w*.4*exp(-d*d*5.0);
    }
    float alpha=vertexColor.a*mask;if(alpha<.003)discard;
    fragColor=vec4(vertexColor.rgb*shine,alpha);
}
