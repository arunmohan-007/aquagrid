import shp from 'shpjs';
import type { ImportFormat } from './catalogue';

/**
 * Turns whatever the operator picked into something the backend already ingests.
 *
 * Shapefiles are converted to GeoJSON **in the browser**. The alternative was a server-side
 * reader, which means GeoTools — tens of megabytes of dependency, a separate artifact repository,
 * and a native-ish parsing surface — to support a format that is, in the end, a zip of four files
 * a 40 kB library already reads. Converting here keeps the server's ingest surface at exactly two
 * formats (GeoJSON and CSV) and means Shapefile support ships without a backend release.
 *
 * GeoJSON and CSV are passed through untouched.
 */
export interface PreparedUpload {
  blob: Blob;
  filename: string;
  /** Features found, when the format lets us count them cheaply. */
  featureCount?: number;
}

export async function prepareUpload(file: File, format: ImportFormat): Promise<PreparedUpload> {
  if (format !== 'SHAPEFILE') {
    return { blob: file, filename: file.name };
  }

  const buffer = await file.arrayBuffer();

  let parsed: unknown;
  try {
    parsed = await shp(buffer);
  } catch (cause) {
    throw new Error(
      'That .zip could not be read as a Shapefile. It must contain .shp, .shx and .dbf files ' +
        `with the same base name. (${(cause as Error).message})`,
    );
  }

  // shpjs returns one FeatureCollection, or an array when the zip holds several layers.
  const collections = (Array.isArray(parsed) ? parsed : [parsed]) as GeoJSON.FeatureCollection[];
  const features = collections.flatMap((collection) => collection?.features ?? []);

  if (features.length === 0) {
    throw new Error('The Shapefile contains no features.');
  }

  const geojson: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features };
  const blob = new Blob([JSON.stringify(geojson)], { type: 'application/geo+json' });

  return {
    blob,
    // The backend picks its parser from the content type; the name is for logs and errors.
    filename: `${file.name.replace(/\.zip$/i, '')}.geojson`,
    featureCount: features.length,
  };
}

/**
 * Warns when a Shapefile zip carries no .prj.
 *
 * Missing projection is the classic silent-corruption case: the import succeeds and the assets
 * land in the wrong hemisphere. Zip stores entry names as raw bytes in each local file header,
 * so this scans for the ASCII sequence rather than decoding the archive — decoding binary as
 * text mangles bytes and produces false alarms.
 *
 * Advisory only. A zip that genuinely lacks a .prj may still be in EPSG:4326 and import fine.
 */
export async function missingProjection(file: File): Promise<boolean> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  const needle = [0x2e, 0x70, 0x72, 0x6a]; // ".prj"
  const upper = [0x2e, 0x50, 0x52, 0x4a]; // ".PRJ"

  for (let i = 0; i <= bytes.length - 4; i++) {
    const b0 = bytes[i]!;
    if (b0 !== needle[0]) continue;
    const lower = bytes[i + 1] === needle[1] && bytes[i + 2] === needle[2] && bytes[i + 3] === needle[3];
    const caps = bytes[i + 1] === upper[1] && bytes[i + 2] === upper[2] && bytes[i + 3] === upper[3];
    if (lower || caps) return false;
  }
  return true;
}
