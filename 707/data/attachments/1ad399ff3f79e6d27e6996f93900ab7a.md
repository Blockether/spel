## DELETE http://localhost:45875/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 16 Aug 2026 15:47:47 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:45875/echo' \
  -X DELETE
```
