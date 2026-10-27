import React from 'react';
import Layout from '@theme/Layout';
import BlogSidebar from '@theme/BlogSidebar';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

import styles from './styles.module.css';

export default function BlogLayout({sidebar, toc, children, ...layoutProps}) {
  const {i18n} = useDocusaurusContext();
  const hasSidebar = sidebar && sidebar.items.length > 0;
  const tocTitle = i18n.currentLocale === 'zh-Hans' ? '本文目录' : 'On this page';

  return (
    <Layout {...layoutProps}>
      <div className={styles.page}>
        <div className={styles.layout}>
          {hasSidebar && <BlogSidebar sidebar={sidebar} />}
          <main className={styles.content}>{children}</main>
          {toc && (
            <aside className={styles.tocColumn} aria-label={tocTitle}>
              <div className={styles.tocTitle}>{tocTitle}</div>
              {toc}
            </aside>
          )}
        </div>
      </div>
    </Layout>
  );
}
