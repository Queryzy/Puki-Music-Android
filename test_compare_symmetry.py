import numpy as np
from PIL import Image, ImageDraw

img = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('L')
arr = np.array(img)[:950, :]

# High quality anti-aliased mask
# White = 255 -> 0, Dark <= 120 -> 255
alpha = np.clip((220.0 - arr) / (220.0 - 110.0) * 255.0, 0, 255).astype(np.uint8)

# Bounding box of original artwork
mask = alpha > 10
y_idx, x_idx = np.where(mask)
ymin, ymax = y_idx.min(), y_idx.max()
xmin, xmax = x_idx.min(), x_idx.max()

crop_a = alpha[ymin:ymax+1, xmin:xmax+1]
ch, cw = crop_a.shape

# Red color: Material Red 600 #E53935 or YouTube Red #FF0000 / #E50914
RED = (229, 30, 45)

def render_white_icon(mode='concentric_disc'):
    size = 512
    img_out = Image.new('RGBA', (size, size), (255, 255, 255, 255))
    draw = ImageDraw.Draw(img_out)
    
    # Red artwork layer
    art_rgba = np.zeros((ch, cw, 4), dtype=np.uint8)
    art_rgba[:, :, 0] = RED[0]
    art_rgba[:, :, 1] = RED[1]
    art_rgba[:, :, 2] = RED[2]
    art_rgba[:, :, 3] = crop_a
    art = Image.fromarray(art_rgba, 'RGBA')
    
    if mode == 'bbox_centered':
        # Fit inside 340x340
        scale = 340 / max(cw, ch)
        nw, nh = int(cw * scale), int(ch * scale)
        res = art.resize((nw, nh), Image.Resampling.LANCZOS)
        ox = (size - nw) // 2
        oy = (size - nh) // 2
        img_out.paste(res, (ox, oy), res)
        
    elif mode == 'concentric_disc':
        # The vinyl disc is a perfect circle with radius 319px centered at X=500, Y=582 in original
        # Disc diameter in original is 638px.
        # Let's scale so disc diameter is 300px in 512 canvas (fits nicely, safe for adaptive circular mask)
        scale = 300.0 / 638.0
        nw, nh = int(cw * scale), int(ch * scale)
        res = art.resize((nw, nh), Image.Resampling.LANCZOS)
        
        # Center of disc in resized art:
        disc_cx = (500 - xmin) * scale
        disc_cy = (582 - ymin) * scale
        
        # We want disc_cx, disc_cy to be at (256, 256 + 25) or (256, 270) so disc is concentric with round icon
        ox = int(256 - disc_cx)
        oy = int(275 - disc_cy)
        img_out.paste(res, (ox, oy), res)
        
    elif mode == 'centroid_balanced':
        # Centroid: X=508, Y=565
        scale = 330.0 / max(cw, ch)
        nw, nh = int(cw * scale), int(ch * scale)
        res = art.resize((nw, nh), Image.Resampling.LANCZOS)
        cen_x = (508.2 - xmin) * scale
        cen_y = (564.6 - ymin) * scale
        ox = int(256 - cen_x)
        oy = int(256 - cen_y)
        img_out.paste(res, (ox, oy), res)

    # Add squircle border for visual preview
    draw.rounded_rectangle([16, 16, size-16, size-16], radius=110, outline=(220, 224, 230, 255), width=3)
    return img_out

render_white_icon('bbox_centered').save('preview_mode_bbox.png')
render_white_icon('concentric_disc').save('preview_mode_disc.png')
render_white_icon('centroid_balanced').save('preview_mode_centroid.png')
print("Rendered 3 modes.")
