## PATCH http://localhost:37239/echo → 200 OK

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
date: Tue, 20 Oct 2026 21:23:24 GMT
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
curl 'http://localhost:37239/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
