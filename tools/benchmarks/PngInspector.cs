using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;

namespace Mca.Benchmark
{
    public sealed class PngQualityInfo
    {
        public long SampleCount { get; internal set; }
        public int LumaP02 { get; internal set; }
        public int LumaP98 { get; internal set; }
        public int LumaDynamicRange { get; internal set; }
        public int RedDynamicRange { get; internal set; }
        public int GreenDynamicRange { get; internal set; }
        public int BlueDynamicRange { get; internal set; }
        public double MeanHorizontalLumaDelta { get; internal set; }
        public double MeanVerticalLumaDelta { get; internal set; }
        public double RowLumaStandardDeviation { get; internal set; }
        public bool IsMonochrome { get; internal set; }
        public bool IsLowDynamicRange { get; internal set; }
        public bool IsHorizontalStriped { get; internal set; }
        public string[] FailureReasons { get; internal set; }

        public bool Passed
        {
            get { return FailureReasons == null || FailureReasons.Length == 0; }
        }
    }

    public sealed class PngInfo
    {
        public int Width { get; internal set; }
        public int Height { get; internal set; }
        public long Bytes { get; internal set; }
        public int ChunkCount { get; internal set; }
        public int BitDepth { get; internal set; }
        public int ColorType { get; internal set; }
        public int InterlaceMethod { get; internal set; }
        public PngQualityInfo Quality { get; internal set; }
    }

    public static class PngInspector
    {
        private const int MaxAnalysisColumns = 512;
        private const long MaximumDecodedImageBytes = 256L * 1024L * 1024L;
        private const int MinimumLumaDynamicRange = 24;
        private const int MaximumMonochromeChannelRange = 8;
        private const double MaximumHorizontalStripeDelta = 6.0;
        private const double MinimumVerticalStripeDelta = 9.0;
        private const double MinimumStripeDeltaRatio = 2.4;
        private const double MinimumStripeRowStandardDeviation = 16.0;

        private static readonly byte[] Signature = new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 };

        private sealed class ParsedPng
        {
            public PngInfo Info;
            public byte[] Idat;
            public byte[] Palette;
        }

        public static PngInfo Inspect(string path)
        {
            return InspectInternal(path, false);
        }

        public static PngInfo InspectQuality(string path)
        {
            return InspectInternal(path, true);
        }

        private static PngInfo InspectInternal(string path, bool includeQuality)
        {
            byte[] data = File.ReadAllBytes(path);
            ParsedPng parsed = Parse(data);
            if (includeQuality)
                parsed.Info.Quality = AnalyzeQuality(parsed);
            return parsed.Info;
        }

        private static ParsedPng Parse(byte[] data)
        {
            if (data.Length < 45) throw new InvalidDataException("PNG is too short.");
            for (int i = 0; i < Signature.Length; i++)
                if (data[i] != Signature[i]) throw new InvalidDataException("PNG signature mismatch.");

            int position = 8;
            int chunks = 0;
            bool sawIhdr = false;
            bool sawIdat = false;
            bool sawIend = false;
            byte[] palette = null;
            PngInfo info = new PngInfo();
            MemoryStream idat = new MemoryStream();
            try
            {
                while (position < data.Length)
                {
                    if (data.Length - position < 12) throw new InvalidDataException("Truncated PNG chunk header.");
                    uint length = ReadUInt32(data, position);
                    position += 4;
                    if (length > Int32.MaxValue) throw new InvalidDataException("PNG chunk is too large.");
                    int payloadLength = (int)length;
                    if ((long)position + 4L + payloadLength + 4L > data.Length)
                        throw new InvalidDataException("Truncated PNG chunk payload.");

                    string type = Encoding.ASCII.GetString(data, position, 4);
                    for (int i = 0; i < 4; i++)
                    {
                        byte value = data[position + i];
                        bool letter = (value >= (byte)'A' && value <= (byte)'Z') ||
                            (value >= (byte)'a' && value <= (byte)'z');
                        if (!letter) throw new InvalidDataException("Invalid PNG chunk type.");
                    }
                    int payloadOffset = position + 4;
                    uint expectedCrc = ReadUInt32(data, payloadOffset + payloadLength);
                    uint actualCrc = ComputeCrc(data, position, 4 + payloadLength);
                    if (expectedCrc != actualCrc) throw new InvalidDataException("PNG CRC mismatch in " + type + ".");

                    chunks++;
                    if (chunks == 1 && type != "IHDR") throw new InvalidDataException("IHDR must be the first PNG chunk.");
                    if (type == "IHDR")
                    {
                        if (sawIhdr || payloadLength != 13) throw new InvalidDataException("Invalid IHDR chunk.");
                        info.Width = checked((int)ReadUInt32(data, payloadOffset));
                        info.Height = checked((int)ReadUInt32(data, payloadOffset + 4));
                        if (info.Width <= 0 || info.Height <= 0) throw new InvalidDataException("PNG dimensions must be positive.");
                        info.BitDepth = data[payloadOffset + 8];
                        info.ColorType = data[payloadOffset + 9];
                        int compressionMethod = data[payloadOffset + 10];
                        int filterMethod = data[payloadOffset + 11];
                        info.InterlaceMethod = data[payloadOffset + 12];
                        if (!HasSupportedBitDepth(info.ColorType, info.BitDepth))
                            throw new InvalidDataException("Unsupported PNG color type or bit depth.");
                        if (compressionMethod != 0 || filterMethod != 0 || (info.InterlaceMethod != 0 && info.InterlaceMethod != 1))
                            throw new InvalidDataException("Unsupported PNG IHDR methods.");
                        sawIhdr = true;
                    }
                    else if (type == "PLTE")
                    {
                        if (!sawIhdr || sawIdat || payloadLength == 0 || payloadLength % 3 != 0 || payloadLength > 768)
                            throw new InvalidDataException("Invalid PLTE chunk.");
                        palette = new byte[payloadLength];
                        Buffer.BlockCopy(data, payloadOffset, palette, 0, payloadLength);
                    }
                    else if (type == "IDAT")
                    {
                        if (!sawIhdr || sawIend) throw new InvalidDataException("Invalid IDAT chunk ordering.");
                        idat.Write(data, payloadOffset, payloadLength);
                        sawIdat = true;
                    }
                    else if (type == "IEND")
                    {
                        if (payloadLength != 0) throw new InvalidDataException("IEND must be empty.");
                        sawIend = true;
                    }

                    position += 4 + payloadLength + 4;
                    if (sawIend)
                    {
                        if (position != data.Length) throw new InvalidDataException("Trailing bytes after IEND.");
                        break;
                    }
                }
            }
            finally
            {
                idat.Close();
            }

            if (!sawIhdr || !sawIdat || !sawIend)
                throw new InvalidDataException("PNG is missing IHDR, IDAT, or IEND.");
            if (info.ColorType == 3 && palette == null)
                throw new InvalidDataException("Indexed-color PNG is missing PLTE.");

            info.Bytes = data.LongLength;
            info.ChunkCount = chunks;
            return new ParsedPng { Info = info, Idat = idat.ToArray(), Palette = palette };
        }

        private static PngQualityInfo AnalyzeQuality(ParsedPng parsed)
        {
            PngInfo info = parsed.Info;
            if (info.InterlaceMethod != 0)
                throw new InvalidDataException("PNG quality analysis requires a non-interlaced PNG.");

            int samplesPerPixel = GetSamplesPerPixel(info.ColorType);
            int bitsPerPixel = checked(samplesPerPixel * info.BitDepth);
            long rowLengthLong = ((long)info.Width * bitsPerPixel + 7L) / 8L;
            long decodedLength = checked((rowLengthLong + 1L) * info.Height);
            if (rowLengthLong > Int32.MaxValue || decodedLength > MaximumDecodedImageBytes)
                throw new InvalidDataException("PNG is too large for quality analysis.");

            byte[] inflated = InflateZlib(parsed.Idat, decodedLength);
            return AnalyzeScanlines(info, parsed.Palette, inflated, (int)rowLengthLong);
        }

        private static PngQualityInfo AnalyzeScanlines(PngInfo info, byte[] palette, byte[] inflated, int rowLength)
        {
            int bytesPerPixel = Math.Max(1, (GetSamplesPerPixel(info.ColorType) * info.BitDepth + 7) / 8);
            int sampleStep = Math.Max(1, (info.Width + MaxAnalysisColumns - 1) / MaxAnalysisColumns);
            int samplesPerRow = (info.Width + sampleStep - 1) / sampleStep;
            byte[] previousRow = new byte[rowLength];
            byte[] currentRow = new byte[rowLength];
            int[] previousLuma = new int[samplesPerRow];
            long[] lumaHistogram = new long[256];
            long[] redHistogram = new long[256];
            long[] greenHistogram = new long[256];
            long[] blueHistogram = new long[256];
            long sampleCount = 0;
            long horizontalPairs = 0;
            long verticalPairs = 0;
            double horizontalDelta = 0.0;
            double verticalDelta = 0.0;
            double rowMeanTotal = 0.0;
            double rowMeanSquares = 0.0;
            int rowCount = 0;
            int position = 0;

            for (int y = 0; y < info.Height; y++)
            {
                if (position >= inflated.Length) throw new InvalidDataException("Truncated PNG scanline data.");
                int filter = inflated[position++];
                if (filter < 0 || filter > 4) throw new InvalidDataException("Unsupported PNG filter type.");
                if (position + rowLength > inflated.Length) throw new InvalidDataException("Truncated PNG scanline data.");

                UnfilterScanline(inflated, position, currentRow, previousRow, bytesPerPixel, filter);
                position += rowLength;

                int priorLuma = 0;
                bool hasPriorLuma = false;
                long rowLumaTotal = 0;
                int sampleIndex = 0;
                for (int x = 0; x < info.Width; x += sampleStep)
                {
                    int red;
                    int green;
                    int blue;
                    GetPixel(info, currentRow, palette, x, out red, out green, out blue);
                    int luma = (54 * red + 183 * green + 19 * blue + 128) >> 8;
                    lumaHistogram[luma]++;
                    redHistogram[red]++;
                    greenHistogram[green]++;
                    blueHistogram[blue]++;
                    sampleCount++;
                    rowLumaTotal += luma;

                    if (hasPriorLuma)
                    {
                        horizontalDelta += Math.Abs(luma - priorLuma);
                        horizontalPairs++;
                    }
                    if (y > 0)
                    {
                        verticalDelta += Math.Abs(luma - previousLuma[sampleIndex]);
                        verticalPairs++;
                    }
                    previousLuma[sampleIndex] = luma;
                    priorLuma = luma;
                    hasPriorLuma = true;
                    sampleIndex++;
                }
                if (sampleIndex == 0) throw new InvalidDataException("PNG has an empty scanline.");
                double rowMean = rowLumaTotal / (double)sampleIndex;
                rowMeanTotal += rowMean;
                rowMeanSquares += rowMean * rowMean;
                rowCount++;

                byte[] swap = previousRow;
                previousRow = currentRow;
                currentRow = swap;
            }
            if (position != inflated.Length) throw new InvalidDataException("Unexpected PNG scanline data.");
            if (sampleCount == 0) throw new InvalidDataException("PNG has no pixels.");

            int lumaP02 = GetPercentile(lumaHistogram, sampleCount, 0.02);
            int lumaP98 = GetPercentile(lumaHistogram, sampleCount, 0.98);
            int redRange = GetPercentile(redHistogram, sampleCount, 0.98) - GetPercentile(redHistogram, sampleCount, 0.02);
            int greenRange = GetPercentile(greenHistogram, sampleCount, 0.98) - GetPercentile(greenHistogram, sampleCount, 0.02);
            int blueRange = GetPercentile(blueHistogram, sampleCount, 0.98) - GetPercentile(blueHistogram, sampleCount, 0.02);
            double meanHorizontal = horizontalPairs == 0 ? 0.0 : horizontalDelta / horizontalPairs;
            double meanVertical = verticalPairs == 0 ? 0.0 : verticalDelta / verticalPairs;
            double meanRow = rowCount == 0 ? 0.0 : rowMeanTotal / rowCount;
            double rowVariance = rowCount == 0 ? 0.0 : rowMeanSquares / rowCount - meanRow * meanRow;
            if (rowVariance < 0.0) rowVariance = 0.0;
            double rowStandardDeviation = Math.Sqrt(rowVariance);

            bool isMonochrome = redRange <= MaximumMonochromeChannelRange &&
                greenRange <= MaximumMonochromeChannelRange && blueRange <= MaximumMonochromeChannelRange;
            int lumaRange = lumaP98 - lumaP02;
            bool isLowDynamicRange = lumaRange < MinimumLumaDynamicRange;
            bool isHorizontalStriped = info.Width >= 16 && info.Height >= 16 &&
                lumaRange >= MinimumLumaDynamicRange &&
                meanHorizontal <= MaximumHorizontalStripeDelta &&
                meanVertical >= MinimumVerticalStripeDelta &&
                meanVertical >= Math.Max(1.0, meanHorizontal) * MinimumStripeDeltaRatio &&
                rowStandardDeviation >= MinimumStripeRowStandardDeviation;

            List<string> failures = new List<string>();
            if (isMonochrome) failures.Add("monochrome");
            if (isLowDynamicRange) failures.Add("low_dynamic_range");
            if (isHorizontalStriped) failures.Add("horizontal_stripes");

            return new PngQualityInfo
            {
                SampleCount = sampleCount,
                LumaP02 = lumaP02,
                LumaP98 = lumaP98,
                LumaDynamicRange = lumaRange,
                RedDynamicRange = redRange,
                GreenDynamicRange = greenRange,
                BlueDynamicRange = blueRange,
                MeanHorizontalLumaDelta = meanHorizontal,
                MeanVerticalLumaDelta = meanVertical,
                RowLumaStandardDeviation = rowStandardDeviation,
                IsMonochrome = isMonochrome,
                IsLowDynamicRange = isLowDynamicRange,
                IsHorizontalStriped = isHorizontalStriped,
                FailureReasons = failures.ToArray()
            };
        }

        private static void UnfilterScanline(byte[] source, int sourceOffset, byte[] current, byte[] previous, int bytesPerPixel, int filter)
        {
            for (int index = 0; index < current.Length; index++)
            {
                int raw = source[sourceOffset + index];
                int left = index >= bytesPerPixel ? current[index - bytesPerPixel] : 0;
                int above = previous[index];
                int upperLeft = index >= bytesPerPixel ? previous[index - bytesPerPixel] : 0;
                int value;
                switch (filter)
                {
                    case 0: value = raw; break;
                    case 1: value = raw + left; break;
                    case 2: value = raw + above; break;
                    case 3: value = raw + ((left + above) >> 1); break;
                    case 4: value = raw + PaethPredictor(left, above, upperLeft); break;
                    default: throw new InvalidDataException("Unsupported PNG filter type.");
                }
                current[index] = unchecked((byte)value);
            }
        }

        private static int PaethPredictor(int left, int above, int upperLeft)
        {
            int estimate = left + above - upperLeft;
            int leftDistance = Math.Abs(estimate - left);
            int aboveDistance = Math.Abs(estimate - above);
            int upperLeftDistance = Math.Abs(estimate - upperLeft);
            if (leftDistance <= aboveDistance && leftDistance <= upperLeftDistance) return left;
            if (aboveDistance <= upperLeftDistance) return above;
            return upperLeft;
        }

        private static void GetPixel(PngInfo info, byte[] row, byte[] palette, int x, out int red, out int green, out int blue)
        {
            int bytesPerSample = info.BitDepth == 16 ? 2 : 1;
            switch (info.ColorType)
            {
                case 0:
                    int gray = ReadSample(row, x, info.BitDepth);
                    red = gray;
                    green = gray;
                    blue = gray;
                    return;
                case 2:
                    int rgbOffset = x * 3 * bytesPerSample;
                    red = row[rgbOffset];
                    green = row[rgbOffset + bytesPerSample];
                    blue = row[rgbOffset + 2 * bytesPerSample];
                    return;
                case 3:
                    int paletteIndex = ReadPackedValue(row, x, info.BitDepth);
                    int paletteOffset = paletteIndex * 3;
                    if (palette == null || paletteOffset + 2 >= palette.Length)
                        throw new InvalidDataException("PNG palette index is out of range.");
                    red = palette[paletteOffset];
                    green = palette[paletteOffset + 1];
                    blue = palette[paletteOffset + 2];
                    return;
                case 4:
                    int grayAlphaOffset = x * 2 * bytesPerSample;
                    int grayAlpha = row[grayAlphaOffset];
                    red = grayAlpha;
                    green = grayAlpha;
                    blue = grayAlpha;
                    return;
                case 6:
                    int rgbaOffset = x * 4 * bytesPerSample;
                    red = row[rgbaOffset];
                    green = row[rgbaOffset + bytesPerSample];
                    blue = row[rgbaOffset + 2 * bytesPerSample];
                    return;
                default:
                    throw new InvalidDataException("Unsupported PNG color type.");
            }
        }

        private static int ReadSample(byte[] row, int x, int bitDepth)
        {
            if (bitDepth == 8) return row[x];
            if (bitDepth == 16) return row[x * 2];
            return ReadPackedSample(row, x, bitDepth);
        }

        private static int ReadPackedSample(byte[] row, int x, int bitDepth)
        {
            int sample = ReadPackedValue(row, x, bitDepth);
            int maximum = (1 << bitDepth) - 1;
            return (sample * 255 + maximum / 2) / maximum;
        }

        private static int ReadPackedValue(byte[] row, int x, int bitDepth)
        {
            int bitOffset = x * bitDepth;
            int byteOffset = bitOffset >> 3;
            int shift = 8 - bitDepth - (bitOffset & 7);
            return (row[byteOffset] >> shift) & ((1 << bitDepth) - 1);
        }

        private static byte[] InflateZlib(byte[] compressed, long expectedLength)
        {
            if (compressed == null || compressed.Length < 6)
                throw new InvalidDataException("PNG IDAT does not contain a zlib stream.");
            int compressionMethod = compressed[0] & 0x0f;
            int compressionInfo = compressed[0] >> 4;
            int flags = compressed[1];
            if (compressionMethod != 8 || compressionInfo > 7 || ((compressed[0] << 8) + flags) % 31 != 0 || (flags & 0x20) != 0)
                throw new InvalidDataException("Invalid PNG zlib header.");
            if (expectedLength < 0 || expectedLength > Int32.MaxValue)
                throw new InvalidDataException("PNG decoded length is invalid.");

            byte[] output;
            using (MemoryStream source = new MemoryStream(compressed, 2, compressed.Length - 6, false))
            using (DeflateStream inflater = new DeflateStream(source, CompressionMode.Decompress))
            using (MemoryStream destination = new MemoryStream((int)expectedLength))
            {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = inflater.Read(buffer, 0, buffer.Length)) > 0)
                {
                    total += read;
                    if (total > expectedLength) throw new InvalidDataException("PNG decompressed data exceeds its dimensions.");
                    destination.Write(buffer, 0, read);
                }
                output = destination.ToArray();
            }
            if (output.LongLength != expectedLength)
                throw new InvalidDataException("PNG decompressed data does not match its dimensions.");
            uint expectedAdler = ReadUInt32(compressed, compressed.Length - 4);
            if (ComputeAdler32(output) != expectedAdler)
                throw new InvalidDataException("PNG zlib Adler-32 mismatch.");
            return output;
        }

        private static int GetSamplesPerPixel(int colorType)
        {
            switch (colorType)
            {
                case 0: return 1;
                case 2: return 3;
                case 3: return 1;
                case 4: return 2;
                case 6: return 4;
                default: throw new InvalidDataException("Unsupported PNG color type.");
            }
        }

        private static bool HasSupportedBitDepth(int colorType, int bitDepth)
        {
            switch (colorType)
            {
                case 0: return bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
                case 2: return bitDepth == 8 || bitDepth == 16;
                case 3: return bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
                case 4: return bitDepth == 8 || bitDepth == 16;
                case 6: return bitDepth == 8 || bitDepth == 16;
                default: return false;
            }
        }

        private static int GetPercentile(long[] histogram, long count, double percentile)
        {
            if (count <= 0) throw new InvalidDataException("PNG quality histogram is empty.");
            long target = (long)Math.Ceiling(count * percentile);
            if (target < 1) target = 1;
            long cumulative = 0;
            for (int value = 0; value < histogram.Length; value++)
            {
                cumulative += histogram[value];
                if (cumulative >= target) return value;
            }
            return histogram.Length - 1;
        }

        private static uint ReadUInt32(byte[] data, int offset)
        {
            return ((uint)data[offset] << 24) | ((uint)data[offset + 1] << 16) |
                ((uint)data[offset + 2] << 8) | data[offset + 3];
        }

        private static uint ComputeCrc(byte[] data, int offset, int count)
        {
            uint crc = 0xffffffffU;
            for (int i = 0; i < count; i++)
            {
                crc ^= data[offset + i];
                for (int bit = 0; bit < 8; bit++)
                    crc = (crc & 1U) != 0 ? 0xedb88320U ^ (crc >> 1) : crc >> 1;
            }
            return crc ^ 0xffffffffU;
        }

        private static uint ComputeAdler32(byte[] data)
        {
            const uint Modulus = 65521U;
            uint low = 1U;
            uint high = 0U;
            for (int i = 0; i < data.Length; i++)
            {
                low = (low + data[i]) % Modulus;
                high = (high + low) % Modulus;
            }
            return (high << 16) | low;
        }
    }
}
