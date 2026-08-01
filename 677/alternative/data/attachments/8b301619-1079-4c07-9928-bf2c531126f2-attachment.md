## PUT http://localhost:33295/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Sat, 01 Aug 2026 19:48:39 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:33295/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
