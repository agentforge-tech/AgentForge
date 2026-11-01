import React from 'react';
import Layout from '@theme/Layout';
import BlogSidebar from '@theme/BlogSidebar';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import clsx from 'clsx';

import styles from './styles.module.css';

export default function BlogLayout({sidebar, toc, children, ...layoutProps}) {
  const {i18n} = useDocusaurusContext();
  const hasSidebar = sidebar && sidebar.items.length > 0;
  const tocTitle = i18n.currentLocale === 'zh-Hans' ? '本文目录' : 'On this page';

  return (
    <Layout {...layoutProps}>
      <div className={styles.page}>
        {hasSidebar && (
          <aside className={styles.sidebarContainer}>
            <div className={styles.sidebarViewport}>
              <BlogSidebar sidebar={sidebar} />
            </div>
          </aside>
        )}
        <main className={clsx(styles.main, {[styles.mainFull]: !hasSidebar})}>
          <div className={styles.container}>
            <div className={styles.contentRow}>
              <div className={styles.content}>{children}</div>
              {toc && (
                <aside className={styles.tocColumn} aria-label={tocTitle}>
                  <div className={styles.tocTitle}>{tocTitle}</div>
                  {toc}
                </aside>
              )}
            </div>
          </div>
        </main>
      </div>
    </Layout>
  );
}
