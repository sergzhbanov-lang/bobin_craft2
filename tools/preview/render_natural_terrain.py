#!/usr/bin/env python3
"""Render headless generator diagnostics. These are not Minecraft screenshots."""
import json
import sys
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
from matplotlib.colors import LightSource, LinearSegmentedColormap, to_rgb
from matplotlib.patches import Patch, Rectangle
import numpy as np

source, destination = map(Path, sys.argv[1:3])
prefix = source.with_suffix('')
metadata = json.loads(Path(f'{prefix}-metadata.json').read_text())
route_colors = ['#ac8050', '#b6a894', '#85734e', '#b39a63']
route_labels = ['Wagon tracks', 'Gravel roads', 'Woodland trails', 'Worn footpaths']
state_colors = ['#879373', '#607c8a', '#427e8b', '#286790', '#c1e5e6']
state_labels = ['Dry ground', 'Still pools', 'Slow water', 'Flowing water', 'Rapids']
land_palette = LinearSegmentedColormap.from_list('forest', ['#aaa17b', '#788566', '#4e674b', '#314d3a'])
depth_palette = LinearSegmentedColormap.from_list('depth', ['#80c3c6', '#438caa', '#154b74', '#102e51'])


def load_grid(path):
    values = np.genfromtxt(path, delimiter=',', names=True)
    xs, zs = np.unique(values['x']), np.unique(values['z'])
    shape = (len(zs), len(xs))
    grids = {name: values[name].reshape(shape) for name in values.dtype.names}
    step = xs[1] - xs[0]
    extent = (xs[0] - step / 2, xs[-1] + step / 2, zs[0] - step / 2, zs[-1] + step / 2)
    return grids, extent, step


def terrain_rgb(grid, step):
    rgb = land_palette(grid['forest'])[..., :3]
    shade = LightSource(azdeg=315, altdeg=45).hillshade(grid['height'], vert_exag=1.4, dx=step, dy=step)
    rgb *= .73 + .27 * shade[..., None]
    wet = grid['depth'] > 0
    rgb[wet] = depth_palette(np.clip((grid['depth'][wet] - 1) / 7, 0, 1))[..., :3]
    route = (np.maximum(grid['road'], grid['trail']) > .2) & ~wet
    for style, color in enumerate(route_colors):
        selected = route & (grid['routeStyle'] == style)
        rgb[selected] = np.asarray(to_rgb(color)) * (.8 + .2 * grid['routeWear'][selected, None])
    return rgb


grid, extent, step = load_grid(source)
detail, detail_extent, detail_step = load_grid(Path(f'{prefix}-water-detail.csv'))
routes = np.genfromtxt(Path(f'{prefix}-routes.csv'), delimiter=',', names=True)
fig, axes = plt.subplots(2, 2, figsize=(16, 14), layout='constrained', facecolor='#f3f2ed')
overview, route_map, water_detail, conditions = axes.flat
overview.imshow(terrain_rgb(grid, step), origin='lower', extent=extent)
overview.set_title('Terrain overview · mountain rivers and shallow shelves', loc='left', fontsize=12)
overview.legend(handles=[Patch(color='#154b74', label='Deep: 3+ blocks'), Patch(color='#80c3c6', label='Shallow: 1–2 blocks')],
                loc='upper left', fontsize=8, framealpha=.94)
overview.add_patch(Rectangle((detail_extent[0], detail_extent[2]), 256, 256, fill=False, edgecolor='#f1cb60', linewidth=1.6))
overview.text(detail_extent[0], detail_extent[3] + 18, 'Water detail below', color='#e8c96b', fontsize=8)

base = terrain_rgb(grid, step)
base = base * .6 + np.asarray([.85, .87, .8]) * .4
route_map.imshow(base, origin='lower', extent=extent)
for style, color in enumerate(route_colors):
    selected = routes[routes['style'] == style]
    if len(selected):
        segments = np.stack([np.stack([selected['x1'], selected['z1']], axis=1),
                             np.stack([selected['x2'], selected['z2']], axis=1)], axis=1)
        route_map.add_collection(LineCollection(segments, colors=color, linewidths=np.clip(selected['width'] / 3, .55, 2.4), alpha=.97))
route_map.set_title('Route network · style and width variation', loc='left', fontsize=12)
route_map.legend(handles=[Patch(color=c, label=n) for c, n in zip(route_colors, route_labels)],
                 loc='upper left', fontsize=8, framealpha=.94)

water_detail.imshow(terrain_rgb(detail, detail_step), origin='lower', extent=detail_extent, interpolation='nearest')
water_detail.set_title('Water depth detail · sampled at one block', loc='left', fontsize=12)
water_detail.legend(handles=[Patch(color='#154b74', label='Deep: 3+ blocks'), Patch(color='#80c3c6', label='Shallow: 1–2 blocks')],
                    loc='upper left', fontsize=8, framealpha=.94)

water_rgb = terrain_rgb(detail, detail_step) * .65 + .2
wet = detail['depth'] > 0
for state, color in enumerate(state_colors[1:], start=1):
    water_rgb[wet & (detail['state'] == state)] = to_rgb(color)
conditions.imshow(water_rgb, origin='lower', extent=detail_extent, interpolation='nearest')
stride = 9
mask = (detail['depth'][::stride, ::stride] > 0) & (np.hypot(detail['flowX'][::stride, ::stride], detail['flowZ'][::stride, ::stride]) > .01)
xs, zs = detail['x'][::stride, ::stride], detail['z'][::stride, ::stride]
conditions.quiver(xs[mask], zs[mask], detail['flowX'][::stride, ::stride][mask], detail['flowZ'][::stride, ::stride][mask],
                  color='#f5e6af', scale=22, width=.0026, headwidth=3)
conditions.set_title('Water conditions · arrows show current direction', loc='left', fontsize=12)
conditions.legend(handles=[Patch(color=c, label=n) for c, n in zip(state_colors[1:], state_labels[1:])],
                  loc='upper left', fontsize=8, framealpha=.94)
for ax in axes.flat:
    ax.set_xlabel('X (blocks)')
    ax.set_ylabel('Z (blocks)')
    ax.set_aspect('equal')
    ax.tick_params(labelsize=8)
for ax in [overview, route_map]:
    ax.scatter([0], [696], marker='o', c='#e4bf65', edgecolors='#202b27', s=30)
    ax.annotate('Landing', (0, 696), xytext=(7, 7), textcoords='offset points', color='#edece2', fontsize=8)
    ax.set_xlim(-1024, 1024)
    ax.set_ylim(-1024, 1024)
    ax.set_xticks([-1024, -512, 0, 512, 1024])
    ax.set_yticks([-1024, -512, 0, 512, 1024])
fig.suptitle(f"Natural generation v{metadata.get('generationVersion', 3)} · seed {metadata['seed']} · {metadata['size']} × {metadata['size']} blocks\n"
             'Procedural diagnostics — not an in-game screenshot', fontsize=16)
fig.savefig(destination, dpi=145, facecolor=fig.get_facecolor())
