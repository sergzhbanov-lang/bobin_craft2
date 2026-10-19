Run the natural terrain diagnostics from the project root with JDK 17+, Python 3,
NumPy and Matplotlib:

```sh
sh tools/preview/run-natural.sh /tmp/bobin-preview 42
```

The script compiles the current version-four pure Java generation code, exports a 2048 × 2048 overview,
samples a varied 256 × 256 water area at one-block resolution, and renders
`natural-terrain.png`. The four panels show water depth, route styles and widths,
a water close-up, and water conditions/current direction. This is a procedural
diagnostic, not an in-game screenshot. CSV files remain beside the image for
inspection. Water depths use each column's local water surface, including elevated
mountain channels; the CSV includes that `waterLevel`.

`waterway-stats.json` measures the complete arena on a two-block grid for seeds
0, 1, 42, 123456789 and -734842. The statistics count the surface area of actual wet columns, including mountain
channels, tributaries, ponds and wetlands. A shallow column contains
one or two water blocks; a deep column contains at least three. Water volume and
river length are not used as area estimates. The report also includes depth and
condition histograms, primary tributary counts, nominal route width ranges and
route styles, elevated water column counts and the water surface elevation range.
