package dev.roots;

/** A convention-routed component that represents one browser page. */
public interface Page extends Component {
    /** Supplies document metadata for the current render.
     * @param context page and session context
     * @return document metadata */
    default Metadata metadata(PageContext context) {
        return Metadata.DEFAULT;
    }

    /** Supplies canonical, robots, theme, and social metadata for the current render.
     * This method may depend on route parameters or page state. Non-empty values declared by
     * {@link dev.roots.annotation.PageMetadata} take precedence.
     * @param context page and session context
     * @return extended document metadata */
    default HeadMetadata headMetadata(PageContext context) {
        return HeadMetadata.EMPTY;
    }

}
