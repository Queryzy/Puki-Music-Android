import numpy as np
from PIL import Image, ImageFilter, ImageDraw

# 1. Load source image, crop out bottom 2px artifact
src = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('L')
src = src.crop((0, 0, 1000, 1000))

# The source has black foreground (< 128) and white background (> 128)
# We want clean alpha mask of the foreground graphic
arr = np.array(src)

# Let's inspect values:
# Disc body is dark (~50). White lines/background are near 255.
# Let's create high resolution clean alpha
