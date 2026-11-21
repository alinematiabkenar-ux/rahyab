"""Complete catalog of all 50 US states + Washington, D.C., neighbor graph (for 30-mile
border-buffer Valhalla routing), and automatic regional splits for very large states
(CA, TX, FL, NY) so every build runs safely inside free GitHub-hosted ubuntu-latest runners."""

from __future__ import annotations

US_STATES = {
    "al": {"abbr": "AL", "name": "Alabama", "slug": "alabama", "bbox": (-88.4732, 30.2233, -84.8891, 35.0080), "neighbors": ["fl", "ga", "ms", "tn"]},
    "ak": {"abbr": "AK", "name": "Alaska", "slug": "alaska", "bbox": (-179.1489, 51.2142, -129.9795, 71.3651), "neighbors": []},
    "az": {"abbr": "AZ", "name": "Arizona", "slug": "arizona", "bbox": (-114.8165, 31.3322, -109.0452, 37.0043), "neighbors": ["ca", "co", "nm", "nv", "ut"]},
    "ar": {"abbr": "AR", "name": "Arkansas", "slug": "arkansas", "bbox": (-94.6179, 33.0041, -89.6444, 36.4997), "neighbors": ["la", "mo", "ms", "ok", "tn", "tx"]},
    "ca": {"abbr": "CA", "name": "California", "slug": "california", "bbox": (-124.4096, 32.5342, -114.1312, 42.0095), "neighbors": ["az", "nv", "or"]},
    "co": {"abbr": "CO", "name": "Colorado", "slug": "colorado", "bbox": (-109.0603, 36.9924, -102.0415, 41.0034), "neighbors": ["az", "ks", "ne", "nm", "ok", "ut", "wy"]},
    "ct": {"abbr": "CT", "name": "Connecticut", "slug": "connecticut", "bbox": (-73.7278, 40.9801, -71.7870, 42.0506), "neighbors": ["ma", "ny", "ri"]},
    "de": {"abbr": "DE", "name": "Delaware", "slug": "delaware", "bbox": (-75.7887, 38.4510, -75.0489, 39.8390), "neighbors": ["md", "nj", "pa"]},
    "dc": {"abbr": "DC", "name": "Washington, D.C.", "slug": "district-of-columbia", "bbox": (-77.1198, 38.7916, -76.9094, 38.9955), "neighbors": ["md", "va"]},
    "fl": {"abbr": "FL", "name": "Florida", "slug": "florida", "bbox": (-87.6349, 24.5231, -80.0314, 31.0009), "neighbors": ["al", "ga"]},
    "ga": {"abbr": "GA", "name": "Georgia", "slug": "georgia", "bbox": (-85.6052, 30.3579, -80.8397, 35.0007), "neighbors": ["al", "fl", "nc", "sc", "tn"]},
    "hi": {"abbr": "HI", "name": "Hawaii", "slug": "hawaii", "bbox": (-160.2471, 18.9104, -154.8068, 22.2356), "neighbors": []},
    "id": {"abbr": "ID", "name": "Idaho", "slug": "idaho", "bbox": (-117.2430, 41.9881, -111.0436, 49.0011), "neighbors": ["mt", "nv", "or", "ut", "wa", "wy"]},
    "il": {"abbr": "IL", "name": "Illinois", "slug": "illinois", "bbox": (-91.5131, 36.9703, -87.4948, 42.5085), "neighbors": ["ia", "in", "ky", "mo", "wi"]},
    "in": {"abbr": "IN", "name": "Indiana", "slug": "indiana", "bbox": (-88.0978, 37.7717, -84.7846, 41.7606), "neighbors": ["il", "ky", "mi", "oh"]},
    "ia": {"abbr": "IA", "name": "Iowa", "slug": "iowa", "bbox": (-96.6397, 40.3755, -90.1401, 43.5012), "neighbors": ["il", "mn", "mo", "ne", "sd", "wi"]},
    "ks": {"abbr": "KS", "name": "Kansas", "slug": "kansas", "bbox": (-102.0517, 36.9930, -94.5884, 40.0032), "neighbors": ["co", "mo", "ne", "ok"]},
    "ky": {"abbr": "KY", "name": "Kentucky", "slug": "kentucky", "bbox": (-89.5715, 36.4971, -81.9650, 39.1475), "neighbors": ["il", "in", "mo", "oh", "tn", "va", "wv"]},
    "la": {"abbr": "LA", "name": "Louisiana", "slug": "louisiana", "bbox": (-94.0431, 28.9286, -88.8170, 33.0195), "neighbors": ["ar", "ms", "tx"]},
    "me": {"abbr": "ME", "name": "Maine", "slug": "maine", "bbox": (-71.0839, 43.0584, -66.9499, 47.4597), "neighbors": ["nh"]},
    "md": {"abbr": "MD", "name": "Maryland", "slug": "maryland", "bbox": (-79.4877, 37.9117, -75.0489, 39.7230), "neighbors": ["dc", "de", "pa", "va", "wv"]},
    "ma": {"abbr": "MA", "name": "Massachusetts", "slug": "massachusetts", "bbox": (-73.5081, 41.2379, -69.9284, 42.8866), "neighbors": ["ct", "nh", "ny", "ri", "vt"]},
    "mi": {"abbr": "MI", "name": "Michigan", "slug": "michigan", "bbox": (-90.4181, 41.6961, -82.4135, 48.2388), "neighbors": ["in", "oh", "wi"]},
    "mn": {"abbr": "MN", "name": "Minnesota", "slug": "minnesota", "bbox": (-97.2392, 43.4994, -89.4917, 49.3844), "neighbors": ["ia", "nd", "sd", "wi"]},
    "ms": {"abbr": "MS", "name": "Mississippi", "slug": "mississippi", "bbox": (-91.6550, 30.1739, -88.0979, 34.9961), "neighbors": ["al", "ar", "la", "tn"]},
    "mo": {"abbr": "MO", "name": "Missouri", "slug": "missouri", "bbox": (-95.7747, 35.9957, -89.0988, 40.6136), "neighbors": ["ar", "ia", "il", "ks", "ky", "ne", "ok", "tn"]},
    "mt": {"abbr": "MT", "name": "Montana", "slug": "montana", "bbox": (-116.0500, 44.3582, -104.0391, 49.0014), "neighbors": ["id", "nd", "sd", "wy"]},
    "ne": {"abbr": "NE", "name": "Nebraska", "slug": "nebraska", "bbox": (-104.0535, 39.9999, -95.3083, 43.0017), "neighbors": ["co", "ia", "ks", "mo", "sd", "wy"]},
    "nv": {"abbr": "NV", "name": "Nevada", "slug": "nevada", "bbox": (-120.0057, 35.0019, -114.0396, 42.0022), "neighbors": ["az", "ca", "id", "or", "ut"]},
    "nh": {"abbr": "NH", "name": "New Hampshire", "slug": "new-hampshire", "bbox": (-72.5572, 42.6970, -70.7036, 45.3055), "neighbors": ["ma", "me", "vt"]},
    "nj": {"abbr": "NJ", "name": "New Jersey", "slug": "new-jersey", "bbox": (-75.5596, 38.9285, -73.8940, 41.3574), "neighbors": ["de", "ny", "pa"]},
    "nm": {"abbr": "NM", "name": "New Mexico", "slug": "new-mexico", "bbox": (-109.0502, 31.3323, -103.0020, 37.0002), "neighbors": ["az", "co", "ok", "tx"]},
    "ny": {"abbr": "NY", "name": "New York", "slug": "new-york", "bbox": (-79.7626, 40.4961, -71.8562, 45.0159), "neighbors": ["ct", "ma", "nj", "pa", "vt"]},
    "nc": {"abbr": "NC", "name": "North Carolina", "slug": "north-carolina", "bbox": (-84.3219, 33.8423, -75.4606, 36.5881), "neighbors": ["ga", "sc", "tn", "va"]},
    "nd": {"abbr": "ND", "name": "North Dakota", "slug": "north-dakota", "bbox": (-104.0489, 45.9351, -96.5545, 49.0006), "neighbors": ["mn", "mt", "sd"]},
    "oh": {"abbr": "OH", "name": "Ohio", "slug": "ohio", "bbox": (-84.8203, 38.4032, -80.5187, 41.9775), "neighbors": ["in", "ky", "mi", "pa", "wv"]},
    "ok": {"abbr": "OK", "name": "Oklahoma", "slug": "oklahoma", "bbox": (-103.0026, 33.6158, -94.4307, 37.0022), "neighbors": ["ar", "co", "ks", "mo", "nm", "tx"]},
    "or": {"abbr": "OR", "name": "Oregon", "slug": "oregon", "bbox": (-124.5662, 41.9918, -116.4635, 46.2920), "neighbors": ["ca", "id", "nv", "wa"]},
    "pa": {"abbr": "PA", "name": "Pennsylvania", "slug": "pennsylvania", "bbox": (-80.5199, 39.7198, -74.6895, 42.2699), "neighbors": ["de", "md", "nj", "ny", "oh", "wv"]},
    "ri": {"abbr": "RI", "name": "Rhode Island", "slug": "rhode-island", "bbox": (-71.8628, 41.1463, -71.1206, 42.0188), "neighbors": ["ct", "ma"]},
    "sc": {"abbr": "SC", "name": "South Carolina", "slug": "south-carolina", "bbox": (-83.3539, 32.0346, -78.5420, 35.2154), "neighbors": ["ga", "nc"]},
    "sd": {"abbr": "SD", "name": "South Dakota", "slug": "south-dakota", "bbox": (-104.0577, 42.4796, -96.4366, 45.9455), "neighbors": ["ia", "mn", "mt", "nd", "ne", "wy"]},
    "tn": {"abbr": "TN", "name": "Tennessee", "slug": "tennessee", "bbox": (-90.3103, 34.9829, -81.6469, 36.6781), "neighbors": ["al", "ar", "ga", "ky", "mo", "ms", "nc", "va"]},
    "tx": {"abbr": "TX", "name": "Texas", "slug": "texas", "bbox": (-106.6456, 25.8374, -93.5083, 36.5007), "neighbors": ["ar", "la", "nm", "ok"]},
    "ut": {"abbr": "UT", "name": "Utah", "slug": "utah", "bbox": (-114.0530, 36.9979, -109.0411, 42.0016), "neighbors": ["az", "co", "id", "nv", "wy"]},
    "vt": {"abbr": "VT", "name": "Vermont", "slug": "vermont", "bbox": (-73.4377, 42.7269, -71.4646, 45.0167), "neighbors": ["ma", "nh", "ny"]},
    "va": {"abbr": "VA", "name": "Virginia", "slug": "virginia", "bbox": (-83.6754, 36.5407, -75.2423, 39.4660), "neighbors": ["dc", "ky", "md", "nc", "tn", "wv"]},
    "wa": {"abbr": "WA", "name": "Washington", "slug": "washington", "bbox": (-124.7631, 45.5435, -116.9156, 49.0025), "neighbors": ["id", "or"]},
    "wv": {"abbr": "WV", "name": "West Virginia", "slug": "west-virginia", "bbox": (-82.6447, 37.2015, -77.7195, 40.6388), "neighbors": ["ky", "md", "oh", "pa", "va"]},
    "wi": {"abbr": "WI", "name": "Wisconsin", "slug": "wisconsin", "bbox": (-92.8894, 42.4919, -86.8054, 47.0806), "neighbors": ["ia", "il", "mi", "mn"]},
    "wy": {"abbr": "WY", "name": "Wyoming", "slug": "wyoming", "bbox": (-111.0569, 40.9947, -104.0522, 45.0059), "neighbors": ["co", "id", "mt", "ne", "sd", "ut"]},
}

# Large states automatically split into regional packs on free GitHub-hosted runners
# so RAM, runner disk, and GitHub Release asset limits stay comfortably safe.
REGIONAL_SPLITS = {
    "ca-north": {
        "abbr": "CA-N",
        "name": "Northern California",
        "parent": "ca",
        "slug": "norcal",
        "bbox": (-124.4096, 36.8000, -118.0000, 42.0095),
        "neighbors": ["or", "nv"],
    },
    "ca-south": {
        "abbr": "CA-S",
        "name": "Southern California",
        "parent": "ca",
        "slug": "socal",
        "bbox": (-121.6000, 32.5342, -114.1312, 37.2000),
        "neighbors": ["az", "nv"],
    },
    "tx-north": {
        "abbr": "TX-N",
        "name": "North Texas",
        "parent": "tx",
        "slug": "texas",
        "bbox": (-103.1000, 31.5000, -93.5083, 36.5007),
        "neighbors": ["ok", "ar", "la", "nm"],
    },
    "tx-south": {
        "abbr": "TX-S",
        "name": "Central & South Texas",
        "parent": "tx",
        "slug": "texas",
        "bbox": (-101.5000, 25.8374, -93.5083, 31.9000),
        "neighbors": ["la"],
    },
    "tx-west": {
        "abbr": "TX-W",
        "name": "West Texas",
        "parent": "tx",
        "slug": "texas",
        "bbox": (-106.6456, 28.9000, -100.8000, 35.0000),
        "neighbors": ["nm"],
    },
    "fl-north": {
        "abbr": "FL-N",
        "name": "North Florida",
        "parent": "fl",
        "slug": "florida",
        "bbox": (-87.6349, 28.2000, -80.5000, 31.0009),
        "neighbors": ["al", "ga"],
    },
    "fl-south": {
        "abbr": "FL-S",
        "name": "Central & South Florida",
        "parent": "fl",
        "slug": "florida",
        "bbox": (-83.2000, 24.5231, -80.0314, 28.6000),
        "neighbors": [],
    },
    "ny-upstate": {
        "abbr": "NY-U",
        "name": "Upstate New York",
        "parent": "ny",
        "slug": "new-york",
        "bbox": (-79.7626, 41.8000, -73.2000, 45.0159),
        "neighbors": ["pa", "vt", "ma", "ct"],
    },
    "ny-downstate": {
        "abbr": "NY-D",
        "name": "NYC & Long Island",
        "parent": "ny",
        "slug": "new-york",
        "bbox": (-75.4000, 40.4961, -71.8562, 42.2000),
        "neighbors": ["nj", "ct", "pa"],
    },
}

LARGE_STATE_REGIONS = {
    "ca": ["ca-north", "ca-south"],
    "tx": ["tx-north", "tx-south", "tx-west"],
    "fl": ["fl-north", "fl-south"],
    "ny": ["ny-upstate", "ny-downstate"],
}

ALL_PACK_CATALOG = {**US_STATES, **REGIONAL_SPLITS}


STATE_NAME_ALIASES: dict[str, str] = {}
for _code, _meta in REGIONAL_SPLITS.items():
    STATE_NAME_ALIASES[_code.lower()] = _code
    STATE_NAME_ALIASES[_meta["abbr"].lower()] = _code
    STATE_NAME_ALIASES[_meta["name"].lower()] = _code
for _code, _meta in US_STATES.items():
    STATE_NAME_ALIASES[_code.lower()] = _code
    STATE_NAME_ALIASES[_meta["abbr"].lower()] = _code
    STATE_NAME_ALIASES[_meta["slug"].lower()] = _code
    STATE_NAME_ALIASES[_meta["slug"].lower().replace("-", " ")] = _code
    STATE_NAME_ALIASES[_meta["name"].lower()] = _code
    STATE_NAME_ALIASES[_meta["name"].lower().replace(".", "").replace(",", "")] = _code
STATE_NAME_ALIASES.update({
    "washington dc": "dc",
    "washington d.c.": "dc",
    "district of columbia": "dc",
})


def resolve_pack_targets(raw_input: str, split_large_states: bool = True) -> list[str]:
    """Expands 'all', a single state ('oregon' or 'or'), or comma-separated states
    ('oregon,washington,california' or 'or,wa,ca') into concrete buildable pack IDs
    (splitting CA/TX/FL/NY into regions by default)."""
    cleaned = raw_input.strip().lower()
    if cleaned == "all":
        tokens = list(US_STATES.keys())
    elif "," in cleaned:
        tokens = [t.strip() for t in cleaned.split(",") if t.strip()]
    else:
        # Handle single full state name with spaces like "new york" or space-separated codes like "or wa"
        if cleaned in STATE_NAME_ALIASES:
            tokens = [cleaned]
        else:
            tokens = [t.strip() for t in cleaned.split() if t.strip()]

    result: list[str] = []
    for raw_token in tokens:
        norm = raw_token.strip().lower().replace(".", "")
        token = STATE_NAME_ALIASES.get(norm, norm)
        if split_large_states and token in LARGE_STATE_REGIONS:
            for region_code in LARGE_STATE_REGIONS[token]:
                if region_code not in result:
                    result.append(region_code)
        elif token in ALL_PACK_CATALOG:
            if token not in result:
                result.append(token)
        else:
            raise ValueError(f"Unknown state or region: '{raw_token}'")
    return result
