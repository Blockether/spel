## PATCH http://localhost:33229/echo → 200 OK

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
date: Fri, 17 Apr 2026 13:13:17 GMT
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
curl 'http://localhost:33229/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
