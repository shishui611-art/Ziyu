"""Weight distance respects an actual variable face's supported axis range."""
def weight_distance(font, requested):
    try:
        if 'fvar' in font:
            axis = next((axis for axis in font['fvar'].axes if axis.axisTag == 'wght'), None)
            if axis is not None:
                return abs(float(requested) - max(float(axis.minValue), min(float(axis.maxValue), float(requested))))
    except Exception:
        pass
    try:
        weight = int(font['OS/2'].usWeightClass)
    except Exception:
        weight = 400
    return abs(weight - requested)
