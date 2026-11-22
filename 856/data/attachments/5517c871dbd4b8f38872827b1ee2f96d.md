## PATCH http://localhost:39327/echo → 200 OK

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
date: Sun, 22 Nov 2026 18:41:44 GMT
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
curl 'http://localhost:39327/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
