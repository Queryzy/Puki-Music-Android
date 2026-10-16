import numpy as np
from PIL import Image, ImageDraw

img = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('L')
arr = np.array(img)[:950, :]

# High quality anti-aliased mask
alpha = np.clip((220.0 - arr) / (220.0 - 110.0) * 255.0, 0, 255).astype(np.uint8)

# Find tight bounding box of all non-transparent pixels (> 10)
mask = alpha > 10
y_idx, x_idx = np.where(mask)
ymin, ymax = y_idx.min(), y_idx.max()
xmin, xmax = x_idx.min(), x_idx.max()

crop_a = alpha[ymin:ymax+1, xmin:xmax+1]
ch, cw = crop_a.shape
print(f"Crop shape: h={ch}, w={cw}")

RED = (229, 30, 45) # Clean vivid red

art_rgba = np.zeros((ch, cw, 4), dtype=np.uint8)
art_rgba[:, :, 0] = RED[0]
art_rgba[:, :, 1] = RED[1]
art_rgba[:, :, 2] = RED[2]
art_rgba[:, :, 3] = crop_a
art = Image.fromarray(art_rgba, 'RGBA')

def make_symmetric_icon(size=512, target_box=310):
    canvas = Image.new('RGBA', (size, size), (255, 255, 255, 255))
    draw = ImageDraw.Draw(canvas)
    
    scale = target_box / max(cw, ch)
    nw, nh = int(cw * scale), int(ch * scale)
    scaled_art = art.resize((nw, nh), Image.Resampling.LANCZOS)
    
    # Calculate exact symmetric margins
    ox = (size - nw) // 2
    oy = (size - nh) // 2
    canvas.paste(scaled_art, (ox, oy), scaled_art)
    
    # Measure margins of non-white pixels
    canvas_arr = np.array(canvas)
    is_not_white = (canvas_arr[:, :, 0] < 250) | (canvas_arr[:, :, 1] < 250) | (canvas_arr[:, :, 2] < 250)
    ys, xs = np.where(is_not_white)
    
    margin_top = ys.min()
    margin_bottom = size - 1 - ys.max()
    margin_left = xs.min()
    margin_right = size - 1 - xs.max()
    print(f"Symmetric Margins: Top={margin_top}, Bottom={margin_bottom}, Left={margin_left}, Right={margin_right}")
    return canvas

c = make_symmetric_icon(512, 310)
c.save('test_symmetric_exact.png')

# Squircle
sq = c.copy()
draw = ImageDraw.Draw(sq)
pad = 16
draw.rounded_rectangle([pad, pad, 512-pad, 512-pad], radius=110, outline=(220, 224, 230, 255), width=3)
sq.save('test_symmetric_squircle.png')

# Circle
ci = c.copy()
draw_ci = ImageDraw.Draw(ci)
draw_ci.ellipse([pad, pad, 512-pad, 512-pad], outline=(220, 224, 230, 255), width=3)
ci.save('test_symmetric_circle.png')
print("Saved squircle and circle.")
