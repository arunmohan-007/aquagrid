/**
 * The import catalogue — what can be imported, as what geometry, from which formats.
 *
 * This is the single source of truth for the hub. The format list is per data type rather than
 * global because the constraint is real: a CSV carries a longitude and a latitude column and
 * nothing else, so it can describe a meter but never a DMA boundary. Offering CSV for a polygon
 * layer would produce an import that fails at row one, which is a worse experience than not
 * offering it.
 */

export type ImportFormat = 'SHAPEFILE' | 'GEOJSON' | 'CSV';

export interface FormatDef {
  id: ImportFormat;
  title: string;
  caption: string;
  /** Accept attribute for the file input. */
  accept: string;
}

export const FORMATS: Record<ImportFormat, FormatDef> = {
  SHAPEFILE: {
    id: 'SHAPEFILE',
    title: 'Shapefile',
    caption: 'A .zip containing .shp, .shx, .dbf and ideally .prj',
    accept: '.zip,application/zip',
  },
  GEOJSON: {
    id: 'GEOJSON',
    title: 'GeoJSON',
    caption: 'A FeatureCollection in EPSG:4326',
    accept: '.json,.geojson,application/json,application/geo+json',
  },
  CSV: {
    id: 'CSV',
    title: 'CSV',
    caption: 'Rows with longitude and latitude columns',
    accept: '.csv,text/csv,text/plain',
  },
};

/** Geometry the layer is expected to carry. Drives which formats are offered. */
export type GeometryKind = 'point' | 'line' | 'polygon';

export interface DataTypeDef {
  id: string;
  title: string;
  caption: string;
  /** Matches the backend `AssetType` enum. */
  assetType: string;
  geometry: GeometryKind;
  formats: ImportFormat[];
}

export interface CategoryDef {
  id: string;
  title: string;
  caption: string;
  /** Icon key resolved in the UI layer; this module stays JSX-free. */
  icon: 'pipe' | 'point' | 'facility' | 'boundary';
  types: DataTypeDef[];
}

/** Lines and polygons cannot come from a CSV; points can come from anything. */
const VECTOR_ONLY: ImportFormat[] = ['SHAPEFILE', 'GEOJSON'];
const POINT_CAPABLE: ImportFormat[] = ['SHAPEFILE', 'GEOJSON', 'CSV'];

export const CATEGORIES: CategoryDef[] = [
  {
    id: 'pipe-network',
    title: 'Pipe Network',
    caption: 'Mains, distribution lines and their attributes',
    icon: 'pipe',
    types: [
      {
        id: 'pipelines',
        title: 'Pipelines',
        caption: 'Line geometry — diameter, material, pressure class',
        assetType: 'PIPELINE',
        geometry: 'line',
        formats: VECTOR_ONLY,
      },
    ],
  },
  {
    id: 'point-assets',
    title: 'Point Assets',
    caption: 'Individually located field assets',
    icon: 'point',
    types: [
      {
        id: 'meters',
        title: 'Water Meters',
        caption: 'Consumer and bulk meters',
        assetType: 'METER',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
      {
        id: 'valves',
        title: 'Valves',
        caption: 'Isolation, air release and scour valves',
        assetType: 'VALVE',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
      {
        id: 'hydrants',
        title: 'Hydrants',
        caption: 'Fire and flushing hydrants',
        assetType: 'HYDRANT',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
      {
        id: 'sensors',
        title: 'Sensors',
        caption: 'Pressure, flow and quality instruments',
        assetType: 'SENSOR',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
      {
        id: 'connections',
        title: 'Service Connections',
        caption: 'Consumer connection points on the network',
        assetType: 'SERVICE_CONNECTION',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
    ],
  },
  {
    id: 'facilities',
    title: 'Facilities',
    caption: 'Tanks, reservoirs, pump stations and wells — footprint or location',
    icon: 'facility',
    types: [
      /*
       * Each facility appears twice, once per geometry. Which one a project holds depends on how
       * it was surveyed: a footprint digitised from imagery, or a single GPS reading taken on
       * site. Splitting them at the type step is what lets the format step stay truthful — the
       * footprint variant never offers CSV.
       *
       * Bore wells are the one exception: a borehole is a shaft, so there is no outline to
       * digitise and offering a footprint variant would invite someone to import the wrong thing.
       */
      {
        id: 'tanks-polygon',
        title: 'Over Head Tanks — footprint',
        caption: 'Polygon outline of the structure',
        assetType: 'TANK',
        geometry: 'polygon',
        formats: VECTOR_ONLY,
      },
      {
        id: 'tanks-point',
        title: 'Over Head Tanks — location',
        caption: 'Single point per tank',
        assetType: 'TANK',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
      {
        id: 'reservoirs-polygon',
        title: 'Reservoirs — footprint',
        caption: 'Polygon outline of the water body',
        assetType: 'RESERVOIR',
        geometry: 'polygon',
        formats: VECTOR_ONLY,
      },
      {
        id: 'reservoirs-point',
        title: 'Reservoirs — location',
        caption: 'Single point per reservoir',
        assetType: 'RESERVOIR',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
      {
        id: 'pumps-polygon',
        title: 'Pump Stations — footprint',
        caption: 'Polygon outline of the station',
        assetType: 'PUMP_STATION',
        geometry: 'polygon',
        formats: VECTOR_ONLY,
      },
      {
        id: 'pumps-point',
        title: 'Pump Stations — location',
        caption: 'Single point per station',
        assetType: 'PUMP_STATION',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
      {
        id: 'open-wells-polygon',
        title: 'Open Wells — footprint',
        caption: 'Polygon outline of the well mouth',
        assetType: 'OPEN_WELL',
        geometry: 'polygon',
        formats: VECTOR_ONLY,
      },
      {
        id: 'open-wells-point',
        title: 'Open Wells — location',
        caption: 'Single point per open well',
        assetType: 'OPEN_WELL',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
      {
        id: 'bore-wells',
        title: 'Bore Wells',
        caption: 'Single point per borehole',
        assetType: 'BORE_WELL',
        geometry: 'point',
        formats: POINT_CAPABLE,
      },
    ],
  },
  {
    id: 'boundaries',
    title: 'Boundaries',
    caption: 'District metered areas and administrative areas',
    icon: 'boundary',
    types: [
      {
        id: 'dma',
        title: 'DMA Boundary',
        caption: 'District metered area — hydraulic district',
        assetType: 'DMA',
        geometry: 'polygon',
        formats: VECTOR_ONLY,
      },
      {
        id: 'panchayat',
        title: 'Panchayat Boundary',
        caption: 'Local-government administrative area',
        assetType: 'PANCHAYAT',
        geometry: 'polygon',
        formats: VECTOR_ONLY,
      },
    ],
  },
];

/*
 * The target-field list used to live here, mirroring a table of constants in BulkImportService.
 * Both are gone. Fields now come from `GET /assets/bulk-import/target-fields`, served from the Data
 * Management catalogue, because a client-side copy of the platform's field list is a copy that goes
 * stale the moment an administrator adds a field — which was the whole point of making the
 * catalogue data. What remains here is what genuinely belongs to the wizard: which data types can
 * be imported, as which geometry, from which file formats.
 */
