"""Create original tileable animated river ripples; no external image assets."""
from pathlib import Path
from PIL import Image
import math, json
root = Path(__file__).resolve().parents[2] / 'src/main/resources'
textures = root / 'assets/bobin_craft/textures/block'
textures.mkdir(parents=True, exist_ok=True)
for name, size in [('river_water_still',16), ('river_water_flow',32)]:
    frames=16
    sheet=Image.new('RGBA',(size,size*frames))
    for frame in range(frames):
        phase=math.tau*frame/frames
        for y in range(size):
            for x in range(size):
                u,v=math.tau*x/size,math.tau*y/size
                a=math.sin(u*2+math.sin(v)+phase)
                b=math.sin(v*3-u+phase*2)
                c=math.cos(u*3+v*2-phase)
                light=int(183+13*a+8*b+5*c)
                foam=max(0,(a+b-1.35))*15
                sheet.putpixel((x,y+frame*size),(int(light+foam),int(light+foam),min(255,int(light+7+foam)),174))
    sheet.save(textures / (name+'.png'))
    (textures / (name+'.png.mcmeta')).write_text(json.dumps({'animation':{'frametime':3,'interpolate':True}},indent=2)+'\n')
atlas=root / 'assets/minecraft/atlases/blocks.json'
atlas.parent.mkdir(parents=True,exist_ok=True)
atlas.write_text(json.dumps({'sources':[{'type':'single','resource':'bobin_craft:block/'+name} for name in ['river_water_still','river_water_flow']]},indent=2)+'\n')
