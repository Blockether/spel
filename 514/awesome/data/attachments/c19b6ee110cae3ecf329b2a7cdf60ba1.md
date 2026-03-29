## PATCH http://localhost:42133/echo → 200 OK

### Request Headers
```
PATCH http://localhost:42133/echo
```

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Sun, 29 Mar 2026 23:19:50 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:42133/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
