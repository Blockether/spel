## DELETE http://localhost:40343/echo → 200 OK

### Request Headers
```
DELETE http://localhost:40343/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 10 Apr 2026 03:53:07 GMT
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
curl 'http://localhost:40343/echo' \
  -X DELETE
```
