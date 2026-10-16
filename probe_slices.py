from PIL import Image
import numpy as np

src = Image.open('/opt/data/cache/images/img_cc3dd75bf56e.jpg').convert('L').crop((0, 0, 1000, 1000))
arr = np.array(src)

# Let's inspect what the image represents:
# Disc outer boundary: where does it start and end?
# Let's check a horizontal slice through the center at y=500
slice_500 = arr[500, :]
# find transitions
transitions = []
for x in range(len(slice_500)-1):
    if (slice_500[x] < 128) != (slice_500[x+1] < 128):
        transitions.append(x)
print("Transitions at y=500:", transitions)

# And vertical slice at x=500
slice_vert = arr[:, 500]
vert_trans = []
for y in range(len(slice_vert)-1):
    if (slice_vert[y] < 128) != (slice_vert[y+1] < 128):
        vert_trans.append(y)
print("Transitions at x=500:", vert_trans)
