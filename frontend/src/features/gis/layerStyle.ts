/**
 * Layer symbol lookup for the chrome around the map — the legend, the layers panel, the search
 * results and the inspection card.
 *
 * This file used to *be* the symbol system: a hard-coded map from layer code to colour and shape,
 * with a grey fallback for anything it did not name. It was the right shape while the layer set was
 * fixed in a migration, and the wrong one the moment Layer Management let an administrator create a
 * layer at runtime — a layer the table did not name rendered grey on the map and grey in the legend,
 * and the only fix was a release.
 *
 * So the table is gone and this is now a small registry filled from the server's composed style.
 * `MapView` paints from the same composition, so a swatch here and the pixels on the map come from
 * one source and cannot disagree — which is the property the old file claimed in its own comments
 * and could only keep for as long as nobody added a layer.
 *
 * The call sites are unchanged: `layerSymbol(code)` still answers, it just answers from
 * configuration now.
 */

import type { ComposedMapLayer } from '@/features/layers/types';

export type SymbolShape =
  | 'circle'
  | 'diamond'
  | 'square'
  | 'triangle'
  | 'hexagon'
  | 'star'
  | 'pin'
  | 'line'
  | 'fill';

export interface LayerSymbol {
  /** Primary symbol fill, as the layer's active style declares it. */
  colour: string;
  /** Soft halo colour, for point halos and swatch glows. */
  glow: string;
  /** Geometry family the swatch should mimic so the legend matches the map. */
  shape: SymbolShape;
}

/**
 * Fallback for a layer whose style has not been composed yet.
 *
 * Neutral grey on purpose. The alternative — inventing a colour per code — is what the deleted table
 * did, and a swatch that guesses is worse than one that visibly says "not styled yet": the guess
 * looks authoritative right up until the map paints something else.
 */
export const LAYER_SYMBOL_FALLBACK: LayerSymbol = {
  colour: '#B9C2D0',
  glow: '#CBD5E1',
  shape: 'circle',
};

/**
 * Module-level rather than React state, and read synchronously.
 *
 * The consumers are deep in the map chrome — a swatch inside a search result row, a dot inside the
 * inspection card's header — and threading the composed style through every one of them as a prop
 * would touch a dozen components to deliver a colour. A registry filled once per style load, read
 * by a plain function, keeps those call sites exactly as they were.
 */
let registry: Record<string, LayerSymbol> = {};

/**
 * Fills the registry from the server's composed style.
 *
 * The first legend entry is the layer's primary symbol: for a simple style it is the only one, and
 * for a classified style it is the first class — which is the right swatch for "this layer", since
 * a single dot cannot show four classes and the legend panel lists them all anyway.
 */
export function registerLayerSymbols(composed: ComposedMapLayer[]): void {
  const next: Record<string, LayerSymbol> = {};
  for (const layer of composed) {
    const primary = layer.legend[0];
    if (!primary) continue;
    next[layer.code] = {
      colour: primary.colour,
      glow: primary.colour,
      shape: (primary.shape as SymbolShape) ?? 'circle',
    };
  }
  registry = next;
}

/** The full symbol (colour + glow + shape) for a layer code. */
export function layerSymbol(code: string): LayerSymbol {
  return registry[code] ?? LAYER_SYMBOL_FALLBACK;
}

/** Flat colour for a layer code — the same value as `layerSymbol(code).colour`. */
export function layerColour(code: string): string {
  return layerSymbol(code).colour;
}
