import os
from PIL import Image, ImageDraw
import numpy as np

img = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('L')
arr = np.array(img)[:950, :]

# High quality anti-aliased mask
# White = 255 -> 0, Dark <= 120 -> 255
alpha = np.clip((220.0 - arr) / (220.0 - 110.0) * 255.0, 0, 255).astype(np.uint8)

# Find tight bounding box of artwork
mask = alpha > 10
y_idx, x_idx = np.where(mask)
ymin, ymax = y_idx.min(), y_idx.max()
xmin, xmax = x_idx.min(), x_idx.max()

crop_a = alpha[ymin:ymax+1, xmin:xmax+1]
ch, cw = crop_a.shape

# Vibrant Red: #E51E2D
RED = (229, 30, 45)

art_rgba = np.zeros((ch, cw, 4), dtype=np.uint8)
art_rgba[:, :, 0] = RED[0]
art_rgba[:, :, 1] = RED[1]
art_rgba[:, :, 2] = RED[2]
art_rgba[:, :, 3] = crop_a
clean_mark = Image.fromarray(art_rgba, 'RGBA')

res_dir = '/opt/data/projects/nuclear-android/app/src/main/res'

densities = {
    'mipmap-mdpi': (48, 108),
    'mipmap-hdpi': (72, 162),
    'mipmap-xhdpi': (96, 216),
    'mipmap-xxhdpi': (144, 324),
    'mipmap-xxxhdpi': (192, 432)
}

# 1. Legacy Squircle with pure white background & subtle light border
def make_legacy_square(size):
    canvas = Image.new('RGBA', (size, size), (255, 255, 255, 255))
    draw = ImageDraw.Draw(canvas)
    pad = max(1, int(size * 0.03))
    radius = int(size * 0.22)
    border_w = max(1, int(size * 0.02))
    
    draw.rounded_rectangle([pad, pad, size - pad, size - pad], radius=radius, fill=(255, 255, 255, 255), outline=(225, 228, 234, 255), width=border_w)
    
    # Target scale for mark: ~60% of size
    target_dim = int(size * 0.60)
    scale = target_dim / max(cw, ch)
    nw, nh = max(1, int(cw * scale)), max(1, int(ch * scale))
    scaled_mark = clean_mark.resize((nw, nh), Image.Resampling.LANCZOS)
    
    ox = (size - nw) // 2
    oy = (size - nh) // 2
    canvas.paste(scaled_mark, (ox, oy), scaled_mark)
    return canvas

# 2. Legacy Circle with pure white background & subtle light border
def make_legacy_round(size):
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    pad = max(1, int(size * 0.02))
    border_w = max(1, int(size * 0.02))
    
    draw.ellipse([pad, pad, size - pad, size - pad], fill=(255, 255, 255, 255), outline=(225, 228, 234, 255), width=border_w)
    
    target_dim = int(size * 0.58)
    scale = target_dim / max(cw, ch)
    nw, nh = max(1, int(cw * scale)), max(1, int(ch * scale))
    scaled_mark = clean_mark.resize((nw, nh), Image.Resampling.LANCZOS)
    
    ox = (size - nw) // 2
    oy = (size - nh) // 2
    canvas.paste(scaled_mark, (ox, oy), scaled_mark)
    return canvas

# 3. Adaptive Icon Foreground (transparent background, mark centered inside 66dp safe zone)
def make_adaptive_fg(size):
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    # Android adaptive safe zone is 66dp of 108dp (~61% max). Use 52% for guaranteed zero mask clipping
    target_dim = int(size * 0.52)
    scale = target_dim / max(cw, ch)
    nw, nh = max(1, int(cw * scale)), max(1, int(ch * scale))
    scaled_mark = clean_mark.resize((nw, nh), Image.Resampling.LANCZOS)
    
    ox = (size - nw) // 2
    oy = (size - nh) // 2
    canvas.paste(scaled_mark, (ox, oy), scaled_mark)
    return canvas

for folder, (legacy_size, fg_size) in densities.items():
    target_dir = os.path.join(res_dir, folder)
    os.makedirs(target_dir, exist_ok=True)
    
    sq = make_legacy_square(legacy_size)
    sq.save(os.path.join(target_dir, 'ic_launcher.png'))
    
    rd = make_legacy_round(legacy_size)
    rd.save(os.path.join(target_dir, 'ic_launcher_round.png'))
    
    fg = make_adaptive_fg(fg_size)
    fg.save(os.path.join(target_dir, 'ic_launcher_foreground.png'))
    
    print(f"Generated {folder}: legacy={legacy_size}px, fg={fg_size}px")

# Update colors.xml for ic_launcher_background -> #FFFFFF
values_dir = os.path.join(res_dir, 'values')
os.makedirs(values_dir, exist_ok=True)
with open(os.path.join(values_dir, 'colors.xml'), 'w') as f:
    f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FFFFFF</color>
</resources>
''')

# Also write app logo in drawable
drawable_dir = os.path.join(res_dir, 'drawable')
os.makedirs(drawable_dir, exist_ok=True)
logo = make_legacy_round(512)
logo.save(os.path.join(drawable_dir, 'ic_app_logo.png'))

# Save a preview for the user
sq_preview = make_legacy_square(512)
sq_preview.save('/opt/data/projects/nuclear-android/preview_white_red_squircle.png')

print("All Android launcher icons regenerated with White background and Red Music mark!")
