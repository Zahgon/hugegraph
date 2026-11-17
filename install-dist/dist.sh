root_path=$(cd $(dirname $0)/..; pwd)
                                    cd $root_path || exit

                                    mkdir -p apache-hugegraph-incubating-1.7.0
                                    cp -r -v $root_path/hugegraph-pd/apache-hugegraph-pd-incubating-1.7.0 apache-hugegraph-incubating-1.7.0/ || exit
                                    cp -r -v $root_path/hugegraph-store/apache-hugegraph-store-incubating-1.7.0 apache-hugegraph-incubating-1.7.0/ || exit
                                    cp -r -v $root_path/hugegraph-server/apache-hugegraph-server-incubating-1.7.0 apache-hugegraph-incubating-1.7.0/ || exit
                                    cp -r -v $root_path/install-dist/release-docs/* $root_path/DISCLAIMER apache-hugegraph-incubating-1.7.0/ || exit

                                    tar zcvf $root_path/target/apache-hugegraph-incubating-1.7.0.tar.gz ./apache-hugegraph-incubating-1.7.0 || exit 1

                                    md5sum $root_path/target/apache-hugegraph-incubating-1.7.0.tar.gz
                                    echo -n "hugegraph tar.gz available at: "
                                    echo "$root_path/target/apache-hugegraph-incubating-1.7.0.tar.gz"