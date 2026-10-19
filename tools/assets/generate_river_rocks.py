#!/usr/bin/env python3
"""Generate original pixel-art rock, pebble and reed resources with Pillow.

Run from any directory. --check validates generated resources without writing;
--preview PATH additionally saves a three-material texture strip.
"""
from pathlib import Path
import argparse
import io

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check', action='store_true')
parser.add_argument('--preview', type=Path)
args = parser.parse_args()
from PIL import Image, ImageDraw
import random, math, json
root=Path(__file__).resolve().parents[2]/'src/main/resources'
assets=root/'assets/bobin_craft'
data=root/'data'

def save_bytes(path, content):
    if args.check:
        if not path.exists() or path.read_bytes() != content:
            raise SystemExit(f"Generated resource is missing or stale: {path.relative_to(root)}")
    else:
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_bytes(content)

def save_image(im, path):
    stream=io.BytesIO()
    im.save(stream, format='PNG')
    save_bytes(path, stream.getvalue())

def write(path,obj):
    save_bytes(path,(json.dumps(obj,indent=2,ensure_ascii=False)+'\n').encode('utf-8'))

def rock(name,base,seed,style,persist=True):
    r=random.Random(seed)
    im=Image.new('RGB',(32,32)); pix=im.load()
    # Clustered grain gives the rocks readable mineral areas rather than flat random static.
    grains=[[r.randint(-12,12) for _ in range(8)] for _ in range(8)]
    for y in range(32):
        for x in range(32):
            v=grains[y//4][x//4]+r.randint(-5,5)
            if style=='slate': v+=int(6*math.sin(y*1.4+x*.08))
            pix[x,y]=tuple(max(0,min(255,c+v)) for c in base)
    d=ImageDraw.Draw(im)
    if style=='river':
        for i in range(13):
            x,y=r.randint(-6,29),r.randint(-5,29);w,h=r.randint(5,10),r.randint(3,7)
            color=tuple(c+r.randint(-6,13) for c in base)
            d.rounded_rectangle((x,y,x+w,y+h),radius=2,fill=color,outline=tuple(max(0,c-20) for c in base))
            d.line((x+2,y,x+w-2,y),fill=tuple(min(255,c+20) for c in base))
    elif style=='slate':
        for y in [3,8,14,21,27]:
            pts=[(x,y+r.choice([-1,0,0,1])) for x in range(0,33,4)]
            d.line(pts,fill=(48,58,64),width=1)
            d.line([(x,yy+1) for x,yy in pts],fill=(106,116,118))
        for i in range(6):
            x,y=r.randrange(32),r.randrange(32)
            d.line((x,y,x-2,y+3,x+1,y+6),fill=(54,63,67))
    else:
        for i in range(13):
            x,y=r.randrange(32),r.randrange(32)
            d.line((x,y,x+2,y+3,x+1,y+5),fill=(73,77,75))
        for i in range(25):
            cx,cy=r.randrange(32),r.randrange(32);radius=r.choice([1,2,3])
            for y in range(max(0,cy-radius),min(32,cy+radius+1)):
                for x in range(max(0,cx-radius),min(32,cx+radius+1)):
                    if (x-cx)**2+(y-cy)**2<=radius**2 and r.random()>.22:
                        pix[x,y]=r.choice([(135,143,112),(157,158,124),(110,125,95),(172,169,132),(119,134,113)])
    if persist: save_image(im, assets/'textures/block'/f'{name}.png')
    return im

river=rock('river_stone',(115,125,124),621,'river')
slate=rock('layered_slate',(78,88,96),422,'slate')
lichen=rock('lichen_rock',(113,116,109),93,'lichen')
# Slate end-grain is irregular exposed stone, with finer short fracture lines.
top=rock('layered_slate_top',(91,101,105),923,'lichen',persist=False)
d=ImageDraw.Draw(top)
for a in [(1,4,15,7),(17,1,21,11),(3,22,11,27),(20,20,30,17)]:d.line(a,fill=(65,75,80))
save_image(top, assets/'textures/block/layered_slate_top.png')

for name in ['river_stone','layered_slate','lichen_rock']:
    if name=='layered_slate':
        model={'parent':'minecraft:block/cube_bottom_top','textures':{'side':'bobin_craft:block/layered_slate','top':'bobin_craft:block/layered_slate_top','bottom':'bobin_craft:block/layered_slate_top'}}
    else:model={'parent':'minecraft:block/cube_all','textures':{'all':f'bobin_craft:block/{name}'}}
    write(assets/'models/block'/f'{name}.json',model)
    write(assets/'models/item'/f'{name}.json',{'parent':f'bobin_craft:block/{name}'})
    write(assets/'blockstates'/f'{name}.json',{'variants':{'':[{'model':f'bobin_craft:block/{name}','y':a} for a in [0,90,180,270]]}})

pebble_shapes=[[[2,0,3,8,4,9],[9,0,9,14,3,14]],[[1,0,7,8,3,14],[9,0,2,15,5,8]],[[3,0,2,12,5,10],[10,0,11,15,2,15]]]
for i,shapes in enumerate(pebble_shapes):
    elements=[]
    for x0,y0,z0,x1,y1,z1 in shapes:
        elements.append({'from':[x0,y0,z0],'to':[x1,y1,z1],'faces':{face:{'texture':'#all'} for face in ['up','down','north','south','east','west']}})
    write(assets/'models/block'/f'river_pebbles_{i}.json',{'textures':{'all':'bobin_craft:block/river_stone','particle':'bobin_craft:block/river_stone'},'elements':elements})
write(assets/'models/item/river_pebbles.json',{'parent':'bobin_craft:block/river_pebbles_0','display':{'gui':{'rotation':[30,225,0],'translation':[0,3,0],'scale':[1.1,1.1,1.1]}}})
write(assets/'blockstates/river_pebbles.json',{'variants':{f'variant={i},waterlogged={str(wet).lower()}':{'model':f'bobin_craft:block/river_pebbles_{i}'} for i in range(3) for wet in [False,True]}})

for half in ['lower','upper']:
    im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im)
    stems=[(8,5),(16,1),(23,9)]
    for x,cap in stems:
        if half=='lower':
            d.line((x,31,x-1,0),fill=(102,120,65,255),width=2)
            d.line((x+1,30,x+1,0),fill=(145,151,87,255))
            for y,dx in [(29,-7),(22,7),(14,-6),(7,5)]:
                d.polygon([(x,y),(x+dx,y-11),(x+dx//2,y-3)],fill=(93,119,66,255))
                d.line((x,y,x+dx,y-11),fill=(132,145,78,255))
        else:
            d.line((x,31,x,cap+3),fill=(134,144,77,255),width=2)
            d.rectangle((x-1,cap+2,x+2,cap+10),fill=(99,72,43,255))
            d.line((x-1,cap+3,x-1,cap+8),fill=(135,104,62,255))
            d.line((x,cap+1,x,cap-1),fill=(113,125,69,255))
            d.polygon([(x,27),(x+7,15),(x+4,25)],fill=(113,134,69,255))
            d.polygon([(x,22),(x-6,10),(x-3,20)],fill=(94,122,64,255))
    save_image(im, assets/'textures/block'/f'river_reeds_{half}.png')
    write(assets/'models/block'/f'river_reeds_{half}.json',{'parent':'minecraft:block/cross','textures':{'cross':f'bobin_craft:block/river_reeds_{half}'}})
write(assets/'blockstates/river_reeds.json',{'variants':{f'half={h}':{'model':f'bobin_craft:block/river_reeds_{h}'} for h in ['lower','upper']}})
write(assets/'models/item/river_reeds.json',{'parent':'minecraft:item/generated','textures':{'layer0':'bobin_craft:block/river_reeds_upper'}})

for name in ['river_stone','layered_slate','lichen_rock','river_pebbles','river_reeds']:
    conditions=[{'condition':'minecraft:survives_explosion'}]
    if name=='river_reeds':conditions.append({'condition':'minecraft:block_state_property','block':'bobin_craft:river_reeds','properties':{'half':'lower'}})
    write(data/'bobin_craft/loot_tables/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'bobin_craft:{name}'}],'conditions':conditions}]})

p=data/'minecraft/tags/blocks/mineable/pickaxe.json'; obj=json.loads(p.read_text())
obj['values']+=['bobin_craft:'+n for n in ['river_stone','layered_slate','lichen_rock','river_pebbles'] if 'bobin_craft:'+n not in obj['values']];write(p,obj)
for lang,values in {'en_us':['River Stone','Layered Slate','Lichen-covered Rock','River Pebbles','River Reeds'],'ru_ru':['Речной камень','Слоистый сланец','Камень с лишайником','Речная галька','Речной камыш'],'uk_ua':['Річковий камінь','Шаруватий сланець','Камінь із лишайником','Річкова галька','Річковий очерет']}.items():
    p=assets/'lang'/f'{lang}.json';obj=json.loads(p.read_text()); obj.update({'block.bobin_craft.'+n:v for n,v in zip(['river_stone','layered_slate','lichen_rock','river_pebbles','river_reeds'],values)});write(p,obj)

if args.preview:
    preview=Image.new('RGB',(320,96),(44,49,42))
    for i,im in enumerate([river,slate,lichen]):preview.paste(im.resize((96,96),Image.Resampling.NEAREST),(i*108,0))
    args.preview.parent.mkdir(parents=True,exist_ok=True)
    preview.save(args.preview)
print('Rock, pebble and reed resources match the generator.' if args.check else 'Generated rock, pebble and reed resources.')
