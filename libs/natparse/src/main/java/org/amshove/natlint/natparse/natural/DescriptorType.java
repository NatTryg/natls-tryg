package org.amshove.natlint.natparse.natural;

import org.amshove.natlint.natparse.NaturalParseException;

public enum DescriptorType
{
	NONE,
	DESCRIPTOR,
	SUPERDESCRIPTOR;

	/**
	 * Constructs the {@link DescriptorType} from source.
	 *
	 * @param source - 1 character long data format (e.g. D for DESCRIPTOR)
	 * @return the typed {@link DescriptorType}
	 */
	public static DescriptorType fromSource(String source)
	{
		switch (source)
		{
			case "D":
				return DESCRIPTOR;
			case "S":
				return SUPERDESCRIPTOR;
			case " ":
				return NONE;
			default:
				throw new NaturalParseException(String.format("Can't determine DescriptorType from \"%s\"", source));
		}
	}
}
