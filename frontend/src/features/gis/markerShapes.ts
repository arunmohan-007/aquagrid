/**
 * Marker shapes, drawn at runtime rather than fetched from a sprite sheet.
 *
 * Layer Style Management lets a point layer render as an icon instead of a circle. The conventional
 * way to serve those icons is a sprite declared in the map style — but every base map here is
 * raster and carries no sprite at all, so an icon style built that way would reference an image
 * MapLibre cannot find and draw nothing. Not an error, just an invisible layer, which is the worst
 * possible feedback for a setting someone has just changed.
 *
 * So the shapes are rasterised into a canvas and registered with `map.addImage` as **SDF** images.
 * SDF is what makes them tintable: a non-SDF image is drawn with its own colours, while an SDF one
 * is drawn in `icon-color` — which is how a classified expression can colour icons by attribute the
 * same way it colours circles. The alpha channel of a hard-edged mask is not a true distance field,
 * so edges are crisper and less scalable than a properly generated SDF; at the icon sizes a utility
 * map uses (10–24 px) that is not visible, and the alternative is shipping and versioning a sprite
 * sheet to gain nothing an operator can see.
 *
 * The names match what the server's style vocabulary offers and what the legend swatch draws, so
 * the picker, the map and the legend cannot show three different shapes for one style.
 */

/** The shapes the style vocabulary offers. Kept in step with `StyleDtos.VocabularyResponse`. */
export const MARKER_SHAPES = [
  'circle',
  'square',
  'diamond',
  'triangle',
  'hexagon',
  'star',
  'pin',
] as const;

export type MarkerShape = (typeof MARKER_SHAPES)[number];

/** MapLibre image ids are prefixed so they cannot collide with a base map's own sprite names. */
export const MARKER_IMAGE_PREFIX = 'ag-';

/**
 * The same shapes as SVG paths on a 24×24 viewBox, for drawing in the DOM.
 *
 * The icon picker and the legend need these shapes as ordinary markup — a canvas per swatch in a
 * grid of a hundred icons is a hundred canvases, and a legend entry has to scale with the
 * surrounding text rather than with a fixed bitmap.
 *
 * They are a second expression of the geometry above, which is a duplication worth naming: the
 * canvas version is what MapLibre rasterises and the SVG version is what a swatch renders, and if
 * they drift the picker shows a shape the map does not draw. They are kept adjacent, in one file,
 * for exactly that reason — and the co-ordinates below are the same construction (a polygon
 * inscribed in a circle, a pin whose tail leaves the head on a tangent) evaluated by hand.
 */
export const MARKER_SHAPE_PATHS: Record<string, string> = {
  circle: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z',
  square: 'M4 4h16v16H4z',
  diamond: 'M12 2l10 10-10 10L2 12z',
  triangle: 'M12 2l10 18H2z',
  hexagon: 'M12 2l8.66 5v10L12 22l-8.66-5V7z',
  star: 'M12 2l2.9 6.4 6.9.7-5.2 4.6 1.5 6.8L12 17l-6.1 3.5 1.5-6.8L2.2 9.1l6.9-.7z',
  pin: 'M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7z',
};

/**
 * Rasterised at 64px and scaled down by `icon-size`.
 *
 * Larger than any icon is drawn, because MapLibre samples an SDF at whatever size the style asks
 * for and upscaling a small mask is what makes runtime icons look like a compromise.
 */
const SIZE = 64;

/**
 * Builds the alpha mask for one shape.
 *
 * Returns MapLibre's `StyleImageInterface` data shape — width, height and RGBA bytes. The colour
 * channels are white throughout; only alpha carries the shape, which is what an SDF image reads.
 */
export function markerImage(shape: MarkerShape): ImageData | null {
  const canvas = document.createElement('canvas');
  canvas.width = SIZE;
  canvas.height = SIZE;
  const ctx = canvas.getContext('2d');
  // A browser that refuses a 2D context is one where the map is not running either; returning null
  // lets the caller skip icon registration rather than throw inside a MapLibre style listener,
  // where an exception unwinds the style load itself and leaves a blank map with no error.
  if (!ctx) return null;

  ctx.fillStyle = '#FFFFFF';
  ctx.beginPath();
  path(ctx, shape);
  ctx.closePath();
  ctx.fill();

  return ctx.getImageData(0, 0, SIZE, SIZE);
}

function path(ctx: CanvasRenderingContext2D, shape: MarkerShape): void {
  const c = SIZE / 2;
  // Inset so the shape's own edge is never clipped by the bitmap boundary, which would leave a
  // flat side on a circle at high icon-size.
  const r = c - 4;

  switch (shape) {
    case 'circle':
      ctx.arc(c, c, r, 0, Math.PI * 2);
      return;
    case 'square':
      ctx.rect(c - r * 0.8, c - r * 0.8, r * 1.6, r * 1.6);
      return;
    case 'diamond':
      polygon(ctx, c, c, r, 4, -Math.PI / 2);
      return;
    case 'triangle':
      polygon(ctx, c, c, r, 3, -Math.PI / 2);
      return;
    case 'hexagon':
      polygon(ctx, c, c, r, 6, -Math.PI / 2);
      return;
    case 'star':
      star(ctx, c, c, r, r * 0.45, 5);
      return;
    case 'pin':
      pin(ctx, c, r);
      return;
  }
}

function polygon(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  radius: number,
  sides: number,
  rotation: number,
): void {
  for (let i = 0; i < sides; i++) {
    const angle = rotation + (i * 2 * Math.PI) / sides;
    const x = cx + radius * Math.cos(angle);
    const y = cy + radius * Math.sin(angle);
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
}

function star(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  outer: number,
  inner: number,
  points: number,
): void {
  for (let i = 0; i < points * 2; i++) {
    const radius = i % 2 === 0 ? outer : inner;
    const angle = -Math.PI / 2 + (i * Math.PI) / points;
    const x = cx + radius * Math.cos(angle);
    const y = cy + radius * Math.sin(angle);
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
}

/**
 * The teardrop map pin: a circle with a point at the bottom.
 *
 * Drawn tip-down and centred on the bitmap rather than anchored at the tip. MapLibre's default
 * `icon-anchor` is the image centre, and matching that keeps a pin sitting where every other shape
 * sits — a pin that silently offset itself would put its asset in a different place from the same
 * asset rendered as a circle.
 */
function pin(ctx: CanvasRenderingContext2D, c: number, r: number): void {
  const headRadius = r * 0.62;
  const headCentre = c - r * 0.25;
  // The two points where the tail leaves the circle, chosen so the tangent is smooth.
  const spread = Math.PI / 3.2;
  ctx.arc(c, headCentre, headRadius, Math.PI - spread, spread, true);
  ctx.lineTo(c, c + r);
}
