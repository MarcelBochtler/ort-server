/*
 * Copyright (C) 2024 The ORT Server Authors (See <https://github.com/eclipse-apoapsis/ort-server/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

import { useQueryClient } from '@tanstack/react-query';
import { createFileRoute, Link } from '@tanstack/react-router';
import {
  createColumnHelper,
  getCoreRowModel,
  getPaginationRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { UserPlus } from 'lucide-react';

import {
  useAdminServiceDeleteApiV1AdminUsers,
  useAdminServiceGetApiV1AdminUsersKey,
} from '@/api/queries';
import { prefetchUseAdminServiceGetApiV1AdminUsers } from '@/api/queries/prefetch';
import { useAdminServiceGetApiV1AdminUsersSuspense } from '@/api/queries/suspense';
import { ApiError, User } from '@/api/requests';
import { DataTable } from '@/components/data-table/data-table';
import { DeleteDialog } from '@/components/delete-dialog';
import { DeleteIconButton } from '@/components/delete-icon-button';
import { LoadingIndicator } from '@/components/loading-indicator';
import { ToastError } from '@/components/toast-error';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { toast } from '@/lib/toast';
import { paginationSearchParameterSchema } from '@/schemas';

const defaultPageSize = 10;

const columnHelper = createColumnHelper<User>();

const columns = [
  columnHelper.accessor('username', {
    header: 'Username',
    cell: ({ row }) => <>{row.original.username}</>,
  }),
  columnHelper.accessor('firstName', {
    header: 'First name',
    cell: ({ row }) => <>{row.original.firstName}</>,
  }),
  columnHelper.accessor('lastName', {
    header: 'Last name',
    cell: ({ row }) => <>{row.original.lastName}</>,
  }),
  columnHelper.accessor('email', {
    header: 'Email address',
    cell: ({ row }) => <>{row.original.email}</>,
  }),
  columnHelper.display({
    id: 'actions',
    header: () => <div className='text-right'>Actions</div>,
    cell: function CellComponent({ row }) {
      const queryClient = useQueryClient();

      const { mutateAsync: delUser } = useAdminServiceDeleteApiV1AdminUsers({
        onSuccess() {
          toast.info('Delete User', {
            description: `User "${row.original.username}" deleted successfully.`,
          });
          queryClient.invalidateQueries({
            queryKey: [useAdminServiceGetApiV1AdminUsersKey],
          });
        },
        onError(error: ApiError) {
          toast.error(error.message, {
            description: <ToastError error={error} />,
            duration: Infinity,
            cancel: {
              label: 'Dismiss',
              onClick: () => {},
            },
          });
        },
      });

      return (
        <div className='flex justify-end'>
          <DeleteDialog
            thingName={'user'}
            thingId={row.original.username}
            uiComponent={<DeleteIconButton />}
            onDelete={() => delUser({ username: row.original.username })}
          />
        </div>
      );
    },
  }),
];

const Users = () => {
  const search = Route.useSearch();
  const pageIndex = search.page ? search.page - 1 : 0;
  const pageSize = search.pageSize ? search.pageSize : defaultPageSize;

  const { data: users } = useAdminServiceGetApiV1AdminUsersSuspense();

  const table = useReactTable({
    data: users || [],
    columns,

    state: {
      pagination: {
        pageIndex,
        pageSize,
      },
    },
    getCoreRowModel: getCoreRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
  });

  return (
    <Card className='h-fit'>
      <CardHeader>
        <CardTitle>Users</CardTitle>
        <CardDescription>
          These are all current users of the server. By clicking the delete
          button in the action column you can delete users, and a written
          confirmation is required to prevent accidental deletions.
        </CardDescription>
        <div className='py-2'>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button asChild size='sm' className='ml-auto gap-1'>
                <Link to='/admin/users/create-user'>
                  Create user
                  <UserPlus className='h-4 w-4' />
                </Link>
              </Button>
            </TooltipTrigger>
            <TooltipContent>Create a new user account.</TooltipContent>
          </Tooltip>
        </div>
      </CardHeader>
      <CardContent>
        <CardContent>
          <DataTable
            table={table}
            setCurrentPageOptions={(currentPage) => {
              return {
                to: Route.to,
                search: { ...search, page: currentPage },
              };
            }}
            setPageSizeOptions={(size) => {
              return {
                to: Route.to,
                search: { ...search, page: 1, pageSize: size },
              };
            }}
          />
        </CardContent>
      </CardContent>
    </Card>
  );
};

export const Route = createFileRoute('/admin/users/')({
  validateSearch: paginationSearchParameterSchema,
  loader: async ({ context }) => {
    prefetchUseAdminServiceGetApiV1AdminUsers(context.queryClient);
  },
  component: Users,
  pendingComponent: LoadingIndicator,
});
