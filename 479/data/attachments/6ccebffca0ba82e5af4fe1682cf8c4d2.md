## PUT http://localhost:39709/echo → 200 OK

### Request Headers
```
PUT http://localhost:39709/echo
```

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
date: Sun, 15 Mar 2026 11:54:40 GMT
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
curl 'http://localhost:39709/echo' \
  -X PUT \
  -d '{"name":"Eve Updated"}'
```
