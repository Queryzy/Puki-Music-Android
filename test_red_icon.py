import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

# 1. Load original image
img = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('L')
arr = np.array(img)

# Remove artifact at bottom (Y >= 950)
arr[950:, :] = 255

# Crop to tight bounding box of the artwork
# Artwork is where arr < 200
mask = arr < 200
y_idx, x_idx = np.where(mask)
ymin, ymax = y_idx.min(), y_idx.max()
xmin, xmax = x_idx.min(), x_idx.max()

# Artwork bounding box
print(f"Artwork bbox: X={xmin}..{xmax} (w={xmax-xmin+1}), Y={ymin}..{ymax} (h={ymax-ymin+1})")

# Extract the artwork section
# In the original artwork:
# - Black (0) is the vinyl body and note stem
# - White (255) inside the disc is the grooves and hole
# - White (255) outside is background

# Let's create an alpha mask where the red should be applied.
# The red should be applied where the original is black (0)!
# Full black = 255 alpha (opaque red). Full white = 0 alpha (transparent).
# Anti-aliasing: alpha = 255 - original_pixel_value
crop_arr = arr[ymin:ymax+1, xmin:xmax+1]
alpha = 255 - crop_arr

# Build RGBA of the red artwork
w = xmax - xmin + 1
h = ymax - ymin + 1

red_rgba = np.zeros((h, w, 4), dtype=np.uint8)
# Vibrant Crimson Red: #E53935 (R=229, G=57, B=53) or #E50914
RED_R, RED_G, RED_B = 229, 30, 45 # Sharp vibrant red
red_rgba[:, :, 0] = RED_R
red_rgba[:, :, 1] = RED_G
red_rgba[:, :, 2] = RED_B
red_rgba[:, :, 3] = alpha

red_art = Image.fromarray(red_rgba, 'RGBA')

# Square box for the art with symmetrical padding
art_dim = max(w, h)
square_art = Image.new('RGBA', (art_dim, art_dim), (0, 0, 0, 0))
# Symmetrically center horizontally and vertically
ox = (art_dim - w) // 2
oy = (art_dim - h) // 2
square_art.paste(red_art, (ox, oy), red_art)

# Test Preview 1: White Square/Squircle (512x512)
size = 512
preview_squircle = Image.new('RGBA', (size, size), (255, 255, 255, 0))
draw = ImageDraw.Draw(preview_squircle)

# Draw white rounded rectangle with clean subtle border
pad = 16
radius = 110
# Subtle shadow / border
draw.rounded_rectangle([pad, pad, size-pad, size-pad], radius=radius, fill=(255, 255, 255, 255), outline=(225, 225, 230, 255), width=3)

# Place red art in center with safe padding (e.g. 68% of inner space)
target_size = int((size - 2 * pad) * 0.72)
scaled_art = square_art.resize((target_size, target_size), Image.Resampling.LANCZOS)
offset = (size - target_size) // 2
preview_squircle.paste(scaled_art, (offset, offset), scaled_art)
preview_squircle.save('preview_red_squircle.png')

# Test Preview 2: White Circle (512x512)
preview_circle = Image.new('RGBA', (size, size), (255, 255, 255, 0))
draw = ImageDraw.Draw(preview_circle)
draw.ellipse([pad, pad, size-pad, size-pad], fill=(255, 255, 255, 255), outline=(225, 225, 230, 255), width=3)

target_size_circle = int((size - 2 * pad) * 0.65)
scaled_art_circle = square_art.resize((target_size_circle, target_size_circle), Image.Resampling.LANCZOS)
offset_circle = (size - target_size_circle) // 2
preview_circle.paste(scaled_art_circle, (offset_circle, offset_circle), scaled_art_circle)
preview_circle.save('preview_red_circle.png')

print("Saved preview_red_squircle.png and preview_red_circle.png")
