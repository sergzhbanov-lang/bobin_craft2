#!/usr/bin/env python3
"""Изометрический рендер чертежа (для визуальной проверки без игры).
Вход: текст "x y z id[props]" по строке на блок (см. DumpBp.java). Выход: PNG.
Использование: python3 render_bp.py dump.txt out.png [yaw 0..3] [scale]"""
import sys, math, colorsys
from PIL import Image, ImageDraw

COL = [
 ("snow", (240,245,250)), ("white_concrete", (235,235,235)), ("quartz", (232,228,220)), ("white_wool",(230,230,230)),
 ("lightning_rod",(200,120,70)), ("iron_bars",(120,125,130)), ("chain",(60,64,70)),
 ("lantern",(255,200,90)), ("campfire",(255,140,40)), ("sea_lantern",(190,230,225)), ("glowstone",(250,210,120)),
 ("glass",(170,210,230)), ("leaves",(46,120,50)), ("grass",(80,140,60)), ("moss",(80,120,40)), ("podzol",(100,70,40)),
 ("mud_brick",(130,100,85)), ("mud",(70,55,50)), ("packed_mud",(120,90,70)),
 ("deepslate",(70,70,76)), ("blackstone",(40,36,44)), ("black_",(30,30,34)), ("basalt",(70,72,76)), ("obsidian",(20,14,30)),
 ("cobblestone",(115,115,115)), ("andesite",(135,135,135)), ("stone",(125,125,125)), ("gravel",(130,126,124)), ("tuff",(110,110,100)),
 ("red_sand",(190,100,40)), ("sandstone",(220,205,150)), ("sand",(225,210,160)), ("terracotta",(150,90,65)),
 ("dirt",(120,85,55)), ("coarse",(110,80,50)),
 ("hay",(200,170,50)), ("target",(220,200,200)), ("barrel",(120,85,45)), ("chest",(150,100,40)),
 ("dark_oak",(60,40,20)), ("spruce",(105,75,45)), ("birch",(200,185,130)), ("acacia",(170,95,55)), ("mangrove",(120,55,50)), ("oak",(160,130,75)),
 ("jungle",(150,110,75)),
 ("red_",(180,40,40)), ("green_",(70,110,40)), ("blue_",(50,70,170)), ("light_blue",(90,160,220)), ("lime_",(120,200,50)),
 ("orange_",(220,120,30)), ("yellow_",(230,200,40)), ("purple_",(120,50,150)), ("gray_",(80,80,84)), ("light_gray",(150,150,154)),
 ("brown_",(100,65,40)), ("pink_",(230,140,170)), ("cyan_",(40,140,150)), ("magenta_",(190,70,190)),
 ("iron",(190,190,195)), ("anvil",(60,60,64)), ("cauldron",(70,70,74)), ("ladder",(130,100,60)), ("fence",(110,80,50)),
 ("door",(120,90,50)), ("bed",(190,50,50)), ("bookshelf",(120,90,50)), ("furnace",(100,100,100)), ("smoker",(100,90,80)),
 ("crafting",(130,90,50)), ("cobweb",(235,235,235)), ("wool",(200,200,200)),
]
def color(bid):
    for k,c in COL:
        if k in bid: return c
    h=(hash(bid)&0xffff)/65535.0
    r,g,b=colorsys.hsv_to_rgb(h,0.4,0.7); return (int(r*255),int(g*255),int(b*255))

def shade(c,f): return tuple(max(0,min(255,int(v*f))) for v in c)

def load(path):
    vox={}
    for ln in open(path,encoding='utf8'):
        ln=ln.strip()
        if not ln: continue
        p=ln.split(' ',3)
        x,y,z=int(p[0]),int(p[1]),int(p[2]); bid=p[3].split('[')[0]
        if bid in('air','cave_air'): continue
        vox[(x,y,z)]=bid
    return vox

def render(vox, yaw=0, S=9, out='out.png', top=False):
    xs=[k[0] for k in vox]; ys=[k[1] for k in vox]; zs=[k[2] for k in vox]
    cx=(min(xs)+max(xs))/2; cz=(min(zs)+max(zs))/2; y0=min(ys)
    def rot(x,z):
        dx,dz=x-cx,z-cz
        for _ in range(yaw): dx,dz=-dz,dx
        return dx,dz
    cells=[]
    for (x,y,z),b in vox.items():
        # visible if any of 6 neighbors empty
        if all(((x+a,y+bb,z+c) in vox) for a,bb,c in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1))): continue
        dx,dz=rot(x,z); cells.append((dx+dz, y, dx, dz, b))
    cells.sort(key=lambda t:(t[0],t[1]))
    W=int((max(xs)-min(xs)+max(zs)-min(zs)+4)*S*0.9)+40; H=int(((max(ys)-y0)*S*1.0)+(max(xs)-min(xs)+max(zs)-min(zs))*S*0.5)+60
    img=Image.new('RGB',(W,H),(150,190,225)); d=ImageDraw.Draw(img)
    ox=W//2; oy=H-30
    for _,y,dx,dz,b in cells:
        c=color(b)
        sx=ox+(dx-dz)*S*0.87; sy=oy-(y-y0)*S*1.0+(dx+dz)*S*0.5
        s=S
        # top
        d.polygon([(sx,sy-s),(sx+s*0.87,sy-s*0.5),(sx,sy),(sx-s*0.87,sy-s*0.5)],fill=shade(c,1.1))
        # left
        d.polygon([(sx-s*0.87,sy-s*0.5),(sx,sy),(sx,sy+s),(sx-s*0.87,sy+s*0.5)],fill=shade(c,0.78))
        # right
        d.polygon([(sx+s*0.87,sy-s*0.5),(sx,sy),(sx,sy+s),(sx+s*0.87,sy+s*0.5)],fill=shade(c,0.58))
    img.save(out)

def plan(vox,out,S=12):
    xs=[k[0] for k in vox]; zs=[k[2] for k in vox]
    top={}
    for (x,y,z),b in vox.items():
        if (x,z) not in top or top[(x,z)][0]<y: top[(x,z)]=(y,b)
    W=(max(xs)-min(xs)+1)*S; H=(max(zs)-min(zs)+1)*S
    img=Image.new('RGB',(W,H),(30,30,30)); d=ImageDraw.Draw(img)
    ys=[v[0] for v in top.values()]; y0=min(ys); y1=max(ys)
    for (x,z),(y,b) in top.items():
        c=shade(color(b),0.6+0.5*(y-y0)/max(1,(y1-y0)))
        px=(x-min(xs))*S; pz=(z-min(zs))*S
        d.rectangle([px,pz,px+S-1,pz+S-1],fill=c)
    img.save(out)

if __name__=='__main__':
    v=load(sys.argv[1]); out=sys.argv[2]
    yaw=int(sys.argv[3]) if len(sys.argv)>3 else 0
    S=int(sys.argv[4]) if len(sys.argv)>4 else 9
    if out.endswith('_plan.png'): plan(v,out)
    else: render(v,yaw,S,out)
