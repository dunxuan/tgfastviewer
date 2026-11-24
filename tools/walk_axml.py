import struct
import sys
import zipfile


def read_pool(data, offset):
    chunk_type, header_size, size = struct.unpack("<HHI", data[offset:offset + 8])
    assert chunk_type == 1, hex(chunk_type)
    count, style_count, flags, strings_start, styles_start = struct.unpack("<IIIII", data[offset + 8:offset + 28])
    print("pool hs=%d size=%d count=%d styles=%d flags=%d start=%d stylesStart=%d"
          % (header_size, size, count, style_count, flags, strings_start, styles_start))
    base = offset + strings_start
    offsets = struct.unpack("<%dI" % count, data[offset + 28:offset + 28 + 4 * count])
    out = []
    for index, item in enumerate(offsets):
        pointer = base + item
        if flags & 0x100:
            length = data[pointer]
            if length & 0x80:
                length = ((length & 0x7F) << 8) | data[pointer + 1]
                pointer += 1
            out.append(data[pointer + 1:pointer + 1 + length].decode("utf-8"))
        else:
            length = struct.unpack("<H", data[pointer:pointer + 2])[0]
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | struct.unpack("<H", data[pointer + 2:pointer + 4])[0]
                pointer += 2
            out.append(data[pointer + 2:pointer + 2 + 2 * length].decode("utf-16-le"))
        if pointer + 8 > len(data):
            print("  OOB at string", index)
    return out, size


def walk(path):
    data = zipfile.ZipFile(path).read("AndroidManifest.xml")
    root_type, root_hs, root_size = struct.unpack("<HHI", data[:8])
    print("root type=%d hs=%d size=%d actual=%d" % (root_type, root_hs, root_size, len(data)))
    strings, pool_size = read_pool(data, 8)
    print("strings", len(strings))
    for index, value in enumerate(strings):
        print("  [%d] %r" % (index, value))
    offset = 8 + pool_size
    while offset + 8 <= len(data):
        chunk_type, header_size, size = struct.unpack("<HHI", data[offset:offset + 8])
        if chunk_type == 0x0180:
            count = (size - 8) // 4
            ids = struct.unpack("<%dI" % count, data[offset + 8:offset + 8 + 4 * count])
            print("resmap", [hex(i) for i in ids])
        elif chunk_type == 0x0102:
            name_index = struct.unpack("<I", data[offset + 20:offset + 24])[0]
            attr_start, attr_size, attr_count = struct.unpack("<HHH", data[offset + 24:offset + 30])
            print("element %r attrs=%d size=%d expect=%d" % (
                strings[name_index], attr_count, size, 16 + 20 + attr_count * 20))
            for index in range(attr_count):
                pointer = offset + 16 + attr_start + index * attr_size
                ns_idx, aname_idx, raw, vsize, res0, dtype, value = struct.unpack("<IIIHBBI", data[pointer:pointer + 20])
                aname = strings[aname_idx]
                shown = strings[value] if dtype == 3 else value
                print("   %-14s ns=%d type=0x%02x value=%r vsize=%d res0=%d" % (aname, ns_idx, dtype, shown, vsize, res0))
        elif chunk_type == 0x0103:
            print("end element")
        offset += size


if __name__ == "__main__":
    walk(sys.argv[1])
