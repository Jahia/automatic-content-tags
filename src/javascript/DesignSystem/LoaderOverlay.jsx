import React from 'react';
import PropTypes from 'prop-types';
import {Loader, Typography} from '@jahia/moonstone';
import styles from './LoaderOverlay.scss';

export const LoaderOverlay = ({status, caption}) => {
    if (!status) {
        return null;
    }

    return (
        <div className={styles.overlay}>
            <Loader size="default"/>
            {caption && (
                <Typography variant="body" className={styles.caption}>
                    {caption}
                </Typography>
            )}
        </div>
    );
};

LoaderOverlay.propTypes = {
    status: PropTypes.bool.isRequired,
    caption: PropTypes.string
};
