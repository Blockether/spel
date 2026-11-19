## DELETE http://localhost:42247/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 19 Nov 2026 13:01:45 GMT
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
curl 'http://localhost:42247/echo' \
  -X DELETE
```
